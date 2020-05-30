# Iterative queries

This guide presents a pattern useful when a dataset must be constructed through a succession of steps, the information produced by each step being required for both streaming and computation of the next step.


## The problem

We'll work on the problem described in [this clojure issue](https://clojure.atlassian.net/browse/CLJ-1906), involving an API exposing a large collection of items with pagination. The service requires the id of the first requested item, and responds with a bounded-size bundle of successive items. As long as the last item is not reached, the id of the next item is returned such that the service can be iteratively requested to get the whole collection. This behavior is implemented in the following mock API, which is almost copy-pasted from the aforementioned issue, except that we added a delay in the API response to emulate network latency. The result is now asynchronous, so we return it as a task.

```clojure
(require '[missionary.core :as m])
(import '(java.util UUID))

(def uuids (repeatedly 10000 #(UUID/randomUUID)))

(def uuid-index
  (loop [uuids uuids
         index  {}]
    (if (seq uuids)
      (recur (rest uuids) (assoc index (first uuids) (rest uuids)))
      index)))

(defn api
  "pages through uuids, 10 at a time. a list-from of :start starts the listing"
  [list-from]
  (let [page (take 10 (if (= :start list-from)
                        uuids (get uuid-index list-from)))]
    (m/sleep 10 {:page page :next (last page)})))
```

The solution suggested in the issue ([refreshed here](https://clojure.atlassian.net/browse/CLJ-2555)) relies on thread blocking and exposes a lazy sequence with a fast reduction path, which is problematic for several reasons.

Calling the network means errors will happen at some point, and unexpected delays must be expected. Lazy sequences don't memoize errors, and don't expose the thread computing the current step. Once built, the sequence fully controls how and when API calls are performed, there's no way to interrupt a pending call or implement any kind of failure recovery. These mechanisms must be implemented upfront, voiding the benefits of sequence composition. `IReduceInit` doesn't have these problems, but is not composable either (not in practice, at least).

In addition, this strategy is not an option in threadless environments such as UI toolkits or browsers.

`missionary` is a better fit for problems of this kind, due to its non-blocking model and its ability to propagate failure and cancellation through composition.


## The antipattern

The first strategy coming to mind is to recursively build successive flows for each API call, prepending the current page to the next flow.

```clojure
(defn pages  
  ([] (pages :start))
  ([id]
   (m/ap
     (let [{:keys [page next]} (m/? (api id))             ;; fetch current page and next id
           rest (if (some? next) (pages next) m/none)]    ;; recursively build the rest of the flow                              
       (m/?? (m/integrate {} page rest))))))              ;; prepend the page and emit the result
```

This implementation seems to work. However, it will crash in the long run because it builds a hierarchy of processes that grows indefinitely for each successive step. The memory footprint will gradually increase, and the stack will ultimately blow up.

This is actually the exact same problem as unbounded recursion in synchronous code. The stack works, it's low-level and fast, but the platform doesn't prevent us to abuse it. `missionary` follows the same guideline, it doesn't trade speed for safety and the user is expected to be aware of some pitfalls. This is one of them, just don't do it.


## The solution

As usual, we can keep stack frames under control using `loop` to make the iteration explicit. A single process represents the whole stream, and each step forks the process in two parts, one emitting a value and the other recursing with the information required to move forward.

```clojure
(defn pages
  ([] (pages :start))
  ([id]
   (m/ap
     (loop [id id]
       (let [x (->> [:page :next]
                    (map (m/? (api id)))         ;; fetch current page and next id
                    (remove nil?)                ;; detect end of stream
                    (m/enumerate)                ;; enumerate branches
                    (m/??))]                     ;; fork the process
         (if (uuid? x) (recur x) x))))))         ;; depending on the branch, emit the value or request more
```

```clojure
(m/? (->> (pages)
          (m/transform (map count))
          (m/aggregate +)))
#_=> 10000
```
