# Comparison to RxJava

*Note*: Please read the guides for tasks and flows before reading this one.

[RxJava](https://github.com/ReactiveX/RxJava) is a popular library providing a reactive streams implementation. We're going to walk through some examples from an [infoq guide](http://web.archive.org/web/20200807102627/https://www.infoq.com/articles/rxjava-by-example/) to compare an RxJava `Observable` to a missionary flow. To follow along grab the latest version of missionary and

```clj
(require '[missionary.core :as m])
```

A missionary flow's closest RxJava cousin would be a [`Flowable`](https://javadoc.io/doc/io.reactivex.rxjava3/rxjava/latest/io/reactivex/rxjava3/core/Flowable.html) since both have backpressure. We'll be sticking to using `Observable` in this guide since the API is largely the same and the InfoQ guide is built with it.

The first part will try to keep the comparisons as close as possible to the RxJava implementation. The second part will try to show alternative or more idiomatic solutions.

## 1. Staying close
### a) Hello world

The almost simplest example of an Observable is

```java
Observable.just("Hello", "World")
          .subscribe(System.out::println);
```

This builds a stream of 2 statically defined values and the subscribe call registers a method that will consume the produced values. The end result is 2 lines on stdout.

While an `Observable` is [full of methods](https://javadoc.io/doc/io.reactivex.rxjava3/rxjava/latest/io/reactivex/rxjava3/core/Observable.html), a missionary flow has only a few [primitive operators](https://cljdoc.org/d/missionary/missionary/b.17/api/missionary.core) that can fulfill the same demands (note that the core namespace includes the whole missionary API, not just flows, so the actual number of functions/macros is lower than you see in the namespace). The most basic one is the fork named `??`:

```clj
(m/? (m/aggregate (constantly nil) (m/ap (println (m/?? (m/enumerate ["Hello" "World"]))))))
```

For those who need a refresher:
- `enumerate` turns a collection into a flow. 
- `??` forks the execution of the current `ap` block. So `(m/ap (println (m/?? ...)))` will be run once for each value, i.e. `(println "Hello")`, then `(println "World")`. It is literally a fork in the execution path. 
- `ap` returns yet again a flow.
- `aggregate` reduces over the flow, returning a task. Since we don't care about the return value in this case we just return nil.
- `?` runs the task.

While there is certainly more functions/macros being called, you'll find that they are completely orthogonal and compose nicely. The end result is a simpler API.

Since this pattern of reducing a flow for a side effect will be present in the few next sections we're define a helper macro to cut on the boilerplate a bit:

```clj
(defmacro drain [flow] `(m/? (m/aggregate (constantly nil) ~flow)))
```

Now our solution shortens to:

```clj
(drain (m/ap (println (m/?? (m/enumerate ["Hello" "World"])))))
```

### b) Zip it

The next example zips two `Observable`s.

```java
List<String> words = Arrays.asList("the", "quick", "brown", "fox", "jumped", "over", "the", "lazy", "dog");
Observable.from(words)
 .zipWith(Observable.range(1, Integer.MAX_VALUE), 
    (string, count)->String.format("%2d. %s", count, string))
 .subscribe(System.out::println);
```

This outputs

```text
 1. the
 2. quick
 3. brown
 4. fox
 5. jumped
 6. over
 7. the
 8. lazy
 9. dog
```

The new methods are `from`, `zipWith` and `range`.

Missionary has a `zip` function as well. No other new functions are needed:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (m/zip (partial format "%2d. %s")
                                     (m/enumerate (range 1 100))
                                     (m/enumerate words)))))))
```

### c) Letters

Next each character gets its own line:

```java
Observable.from(words)
 .flatMap(word -> Observable.from(word.split("")))
 .zipWith(Observable.range(1, Integer.MAX_VALUE),
   (string, count) -> String.format("%2d. %s", count, string))
 .subscribe(System.out::println);
```

This outputs:

```text
 1. t
 2. h
 3. e
 4. q
 5. u
 6. i
 7. c
 8. k
<...omitted...>
30. l
31. a
32. z
33. y
34. d
35. o
36. g
```

The only new method is `flatMap`.

No new methods are needed for the missionary implementation, we just fork some more:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (m/zip (partial format "%2d. %s")
                                     (m/enumerate (range 1 100))
                                     (m/ap (m/?? (m/enumerate (m/?? (m/enumerate words)))))))))))
```

*Note*: the nested `ap` call is necessary to not fork the whole block. If we had written

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (m/zip (partial format "%2d. %s")
                                     (m/enumerate (range 1 100))
                                     (m/enumerate (m/?? (m/enumerate words)))))))))
```

we would get

```text
 1. t
 2. h
 3. e
 1. q
 2. u
 3. i
 4. c
 5. k
 1. b
 2. r
 3. o
 4. w
 5. n
<...omitted...>
```

*Note*: typically a clojure programmer would sooner write

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (m/zip (partial format "%2d. %s")
                                     (m/enumerate (range 1 100))
                                     (m/enumerate (mapcat seq words))))))))
```

in this case. However that wouldn't be the same as the RxJava code that actually flatmapped over an `Observable`.


### d) Distinct letters

The guide follows by printing only the distinct letters:

```java
Observable.from(words)
 .flatMap(word -> Observable.from(word.split("")))
 .distinct()
 .zipWith(Observable.range(1, Integer.MAX_VALUE),
   (string, count) -> String.format("%2d. %s", count, string))
 .subscribe(System.out::println);
 ```
 
 This outputs:
 
 ```text
 1. t
 2. h
 3. e
 4. q
 5. u
 6. i
 7. c
 8. k
 9. b
10. r
11. o
12. w
13. n
14. f
15. x
16. j
17. m
18. p
19. d
20. v
21. l
22. a
23. z
24. y
25. g
```

One new method, `distinct`, is introduced here.

We will introduce a new function in missionary as well, a much more general one though, `transform`. It takes a transducer and returns a new flow, transforming the values along the way. We don't need to reimplement disticnt for flows but can reuse a generic transducer:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (m/zip (partial format "%2d. %s")
                                     (m/enumerate (range 1 100))
                                     (->> (m/ap (m/?? (m/enumerate (m/?? (m/enumerate words)))))
                                          (m/transform (distinct)))))))))
```


### e) Sorted distinct letters

To spot the missing letter the guide sorts them:

```java
.flatMap(word -> Observable.from(word.split("")))
 .distinct()
 .sorted()
 .zipWith(Observable.range(1, Integer.MAX_VALUE),
   (string, count) -> String.format("%2d. %s", count, string))
 .subscribe(System.out::println);
```

This outputs:

```text
 1. a
 2. b
 3. c
<...omitted...>
17. q
18. r
19. t
20. u
21. v
22. w
23. x
24. y
25. z
```

1 new method is introduced, `sorted`.

There is no sort function in missionary and there isn't a transducer available either since sorting requires consuming the whole collecition. No new functions are needed though, we just need to `aggregate` the flow and `enumerate`it:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (m/zip (partial format "%2d. %s")
                                     (m/enumerate (range 1 100))
                                     (->> (m/ap (m/?? (m/enumerate (m/?? (m/enumerate words)))))
                                          (m/aggregate conj (sorted-set)) m/? m/enumerate)))))))
```

### f) Merge

The next example merges 2 `Observable`s into 1:

```java
private static long start = System.currentTimeMillis();
public static Boolean isSlowTickTime() {
   return (System.currentTimeMillis() - start) % 30_000 >= 15_000;
}
Observable<Long> fast = Observable.interval(1, TimeUnit.SECONDS);
Observable<Long> slow = Observable.interval(3, TimeUnit.SECONDS);
Observable<Long> clock = Observable.merge(
       slow.filter(tick-> isSlowTickTime()),
       fast.filter(tick-> !isSlowTickTime())
);
clock.subscribe(tick-> System.out.println(new Date()));
Thread.sleep(60_000);
```

The `clock` `Observable` should emit a value every second for 15 seconds, then every 3 seconds for 15 seconds, in a loop. The example output is

```text
Fri Sep 16 03:08:18 BST 2016
Fri Sep 16 03:08:19 BST 2016
Fri Sep 16 03:08:20 BST 2016
Fri Sep 16 03:08:21 BST 2016
Fri Sep 16 03:08:22 BST 2016
Fri Sep 16 03:08:23 BST 2016
Fri Sep 16 03:08:24 BST 2016
Fri Sep 16 03:08:25 BST 2016
Fri Sep 16 03:08:26 BST 2016
Fri Sep 16 03:08:27 BST 2016
Fri Sep 16 03:08:28 BST 2016
Fri Sep 16 03:08:29 BST 2016
Fri Sep 16 03:08:30 BST 2016
Fri Sep 16 03:08:31 BST 2016
Fri Sep 16 03:08:32 BST 2016
Fri Sep 16 03:08:35 BST 2016
Fri Sep 16 03:08:38 BST 2016
Fri Sep 16 03:08:41 BST 2016
Fri Sep 16 03:08:44 BST 2016
```

The new methods are `interval`, `filter` and `merge`.

Missionary has another fork operator, the concurrent fork `?=`. It forks the execution for all values coming in from the flow concurrently. With this new operator, the `sleep` task that sleeps for the given amount of milliseconds and what we learned so far we can build everything we need:

```clj
(let [start (System/currentTimeMillis)]
  (defn is-slow-tick-time [] (-> (System/currentTimeMillis) (- start) (mod 30000) (>= 15000))))
(defn interval [ms] (m/ap (loop [] (if (m/?? (m/enumerate [true false])) :emit (do (m/? (m/sleep ms)) (recur))))))
(def fast (interval 1000))
(def slow (interval 3000))
(def clock (m/ap (m/?? (m/?= (m/enumerate [(m/transform (filter (fn [_] (is-slow-tick-time))) slow)
                                           (m/transform (filter (fn [_] (not (is-slow-tick-time)))) fast)])))))
(m/? (m/aggregate (fn [_ _] (println (java.util.Date.))) nil (m/transform (take 20) clock)))
```

### g) Plug in

The InfoQ example using an `AsyncEmitter` to turn a listener into a cold `Observable` is out of date (not present in RxJava 3). For completeness we will copy it here though. If anyone knows a RxJava3 solution feel free to submit a PR!

```java
SomeFeed<PriceTick> feed = new SomeFeed<>(); 
Observable<PriceTick> obs = 
  Observable.fromEmitter((AsyncEmitter<PriceTick> emitter) -> 
  { 
    SomeListener listener = new SomeListener() { 
      @Override 
      public void priceTick(PriceTick event) { 
        emitter.onNext(event); 
        if (event.isLast()) { 
          emitter.onCompleted(); 
        } 
      } 

      @Override 
      public void error(Throwable e) { 
        emitter.onError(e); 
      } 
    }; 
    feed.register(listener); 
  }, AsyncEmitter.BackpressureMode.BUFFER); 
```

The example shows a `SomeFeed` that can `register` a new `SomeListener` which receives `priceTick` and `error` events and can ask if a `PriceTick` event was the last one via `isLast`. This listener is then converted to an `Observable`.

A flow doesn't provide callbacks that we could use to emit values or signal error/completion. We can achieve something similar by using a queue. We will use a missionary `mbx` (short for mailbox). `(mbx)` returns a mailbox that can be used as a 1-arity function to send a value and as a task to wait for a value.

```clj
(defn feed->flow [feed]
  (let [mbx (m/mbx)]
    (.register feed (reify SomeListener
                      (priceTick [event] (mbx [:val event]) (when (.isLast event) (mbx [:done])))
                      (error [e] (mbx [:err e]))))
    (m/ap (loop [[t v] (m/? mbx)]
            (case t
              :val (if (m/?? (m/enumerate [true false])) v (recur (m/? mbx)))
              :err (throw e)
              :done (m/?? m/none))))))
```

## 2. Diverging

An interesting style is to think more in terms of functional `transform`ations over a given flow (a.k.a. transducers).

In `1b` we don't strictly need to zip two flows, we're just interested in indexing the entries. This can be more easily achieved with the `map-indexed` transducer:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (->> (m/enumerate words)
                                   (m/transform (map-indexed #(format "%2d. %s" (inc %1) %2)))))))))
```

In `1c` we don't need to `flatMap` or double-fork the flow, we can rely on transducers again:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (->> (m/enumerate words)
                                   (m/transform (comp (mapcat seq)
                                                      (map-indexed #(format "%2d. %s" (inc %1) %2))))))))))
```

In `1d` we already used a transducer, so now we just need to plug it in:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (->> (m/enumerate words)
                                   (m/transform (comp (mapcat seq)
                                                      (distinct)
                                                      (map-indexed #(format "%2d. %s" (inc %1) %2))))))))))
```

In `1e` we have to break out of our pattern a bit since we need to sort the letters:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (->> (m/enumerate words)
                                   (m/transform (comp (mapcat seq) (distinct)))
                                   (m/aggregate conj (sorted-set)) m/? m/enumerate
                                   (m/transform (map-indexed #(format "%2d. %s" (inc %1) %2)))))))))
```

The "have to" is a bit strong, noone can stop us from implementing our own sorting transducer!

```clj
(defn tsort []
  (fn [rf]
    (let [b (java.util.ArrayList.)]
      (fn
        ([] (rf))
        ([result]
         (if (.isEmpty b)
           result
           (let [vs (sort (.toArray b)), rt (reduce rf (unreduced result) vs)]
             (.clear b)
             (rf rt))))
        ([result input] (.add b input) result)))))
```

Now we can return to a single transducer solution:

```clj
(let [words ["the" "quick" "brown" "fox" "jumped" "over" "the" "lazy" "dog"]]
  (drain (m/ap (println (m/?? (->> (m/enumerate words)
                                   (m/transform (comp (mapcat seq) (distinct) (tsort)
                                                      (map-indexed #(format "%2d. %s" (inc %1) %2))))))))))
```

As you can see transducers make the solutions simpler, shorter and easier to reason about.
