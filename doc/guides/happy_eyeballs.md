# Happy eyeballs

Happy eyeballs ([RFC8305](https://tools.ietf.org/html/rfc8305)) is an algorithm describing the recommended connection strategy for a client attempting to reach a host when the DNS query returns multiple IP addresses. It happens to be notoriously hard to implement with traditional imperative techniques, and recently became a [showcase problem](https://www.youtube.com/watch?v=oLkfnc_UMcE&t=286) for structured concurrency.

In this guide, we'll implement it with `missionary`. Given a sequence of tasks performing the connection to each endpoint, we want to build a task sequentially performing connection attempts, staggered by given delay (in milliseconds). The first connector is tried, then if it's still pending after staggering delay or when it fails the second connector is tried, and so on until the connector sequence is exhausted. The first successful connection attempt triggers cancellation of other pending connection attempts and makes the whole task terminate with returned socket. If another connection succeeds in the interim, returned sockets must be closed immediately. If every attempt fails, the whole task fails. Cancelling the task triggers cancellation of all pending connection attempts.
```clojure
(require '[missionary.core :as m])
```

The core of the algorithm is the `attempt` function, recursively building successive connection attempts. `chosen` is a dataflow variable holding the fastest connection, `delay` is the staggering delay in milliseconds, `close!` is a function closing given socket, `connectors` is a sequence of tasks performing a connection attempt on a single endpoint.
```clojure
(defn attempt [chosen delay close! connectors]
  (if-some [[connector & connectors] connectors]
    ;; assigning this dataflow variable will trigger next attempt
    (let [trigger (m/dfv)]
      (m/race
        ;; try to connect to endpoing
        (m/sp (try (let [x (m/? connector)
                         y (chosen x)]
                     ;; if another attempt succeeded, close socket before terminate
                     (when-not (identical? x y) (close! x)) y)
                   (catch Throwable e
                     ;; if attempt fails early, trigger next attempt immediately
                     (trigger nil)
                     (throw e))))
        ;; trigger next attempt if still pending after delay
        (m/sp (m/? (m/sleep delay))
              (trigger nil)
              (m/? m/never))
        ;; wait for trigger and recursively try next endpoints
        (m/sp (m/? trigger)
              (m/? (attempt chosen delay close! connectors)))))
    ;; no more endpoint, return a failing task              
    (m/race)))
```

Now, we bind this recursive function to a fresh `chosen` dataflow variable.
```clojure
(defn happyeyeballs [delay close! connectors]
  (m/sp
    (try
      (m/? (attempt (m/dfv) delay close! connectors))
      (catch Throwable _
        (throw (ex-info "Unable to reach target."
                 {:delay delay
                  :close! close!
                  :connectors connectors}))))))
```

Time to test now.
```clojure
(defn connector [^java.net.InetAddress addr port]
  (m/via m/blk (java.net.Socket. addr (int port))))

(defn endpoints [^String host]
  (m/via m/blk (java.net.InetAddress/getAllByName host)))

(defn connectors [^String host port]
  (m/sp (map #(connector % port) (m/? (endpoints host)))))

(defn close! [^java.net.Socket socket]
  (.close socket))

(defn connect [^String host port delay]
  (m/sp (m/? (happyeyeballs delay close! (m/? (connectors host port))))))

(m/? (connect "clojure.org" 80 300))
```
