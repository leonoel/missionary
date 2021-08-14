(ns readme2
  (:require [missionary.core :as m]
            [hyperfiddle.rcf :as rcf :refer [tests ! %]]))

(tests
  "async tests demo"
  ; rcf/! taps the value to a queue for later checking
  (rcf/! :a)
  (! :b)
  % := :a
  % := :b
  % := ::rcf/timeout)

(tests
  "a task is an async effect (an async single-value producer with failure)"
  (def task (fn [success! failure!]
              ; low level interface for your understanding, never do this.
              ; tasks are arity-2 fns that take success/failure continuations and return a
              ; cancellation callback. see https://github.com/leonoel/task
              (let [fut (future (Thread/sleep 50) (success! 42))]
                (fn cancel [] (.cancel fut true)))))
  (def cancel! (task (fn success [v] (! v))
                     (fn failure [e] (! e))))
  % := 42

  "task cancellation"
  ; note we can reuse the same task, as it is a stateless recipe
  (def cancel! (task (fn success [v] (! v))
                     (fn failure [e] (! ::cancelled))))
  (cancel!)
  % := ::cancelled

  "m/sleep is a task factory"
  (def task (m/sleep 0 42))
  (def cancel! (task ! !))
  % := 42)

(tests
  "m/sp composes tasks into sequential processes, m/? is await"
  (def task (m/sp (inc (m/? (m/sleep 0 (inc (m/? (m/sleep 0 1))))))))
  (task ! !)
  % := 3

  "m/sp embeds a clojure analyzer, lifting clojure forms into an async context"
  (def task (m/sp (let [a (m/? (m/sleep 50 1))
                        b (m/? (m/sleep 50 (inc a)))
                        c (inc b)]
                    c)))
  (task ! !)
  ; 100 ms
  % := 3

  "tasks are values, they do not run until you await them"
  (def task (m/sp (let [a (m/sleep 50 :a)                   ; value
                        b (m/sleep 50 :b)]                  ; value
                    ; m/sp is sequential, m/sp is never parallel
                    [(m/? a) (m/? b)])))
  (task ! !)
  ; 100ms, not 50 ms
  % := [:a :b]

  "m/sp runtime control flow (monadic composition)"
  (def nap (m/sleep 0 42))
  (def task (m/sp (m/? (if (odd? (m/? nap))                 ; runtime control flow
                         (m/sleep 10 ::odd)
                         (m/sleep 10 ::even)))))
  (task ! !)
  % := ::even)

(tests
  "concurrent tasks (running in parallel)"
  ; tasks are pure values, thus reusable (promises are not)
  (let [x-task (m/sleep 800 :x)]
    (! (m/? (m/join vector x-task x-task))))
  ; 800 ms sleep, not 1600
  % := [:x :x])

(tests
  "discrete flow (eager event stream with backpressure)"
  (def !x (atom 0))
  ; low level interface, to show how it works but don't do this
  (def sampler ((m/ap (! (m/?< (m/watch !x))))
                (fn ready [] (println ::ready))
                (fn done [] (println ::done))))
  ; ::ready
  @sampler := 0
  (swap! !x inc)
  ; ::ready
  @sampler := 1
  (swap! !x inc)
  ; ::ready, my buffer is non-empty with an eager 2
  (swap! !x inc) ; backpressure causes the watch to drop this state
  (swap! !x inc) ; next time buffer is non-full, we will see this state
  @sampler := 2  ; effect of transferring (accepting) the 2 made the buffer non-full, triggering resume of m/ap process
  ; ::ready
  @sampler := 4
  ; ::ready
  )

(tests
  (def !x (atom 0))
  (def df (m/eduction (map !) (m/watch !x))) ; discrete flow that taps each state of the atom
  ; use reduce to drive the flow for effect (sampling as fast as possible), otherwise backpressure will halt it
  ; this use of reduce is weird and just a test idiom, you wouldn't do this in an app
  (def task (m/reduce (constantly nil) df))
  (def cancel (task ! !))
  (swap! !x inc)
  (swap! !x inc)
  (swap! !x inc)
  % := 0
  % := 1
  % := 2
  ; reduce will continue until the flow terminates or the reduce is cancelled.
  ; Watching an atom will never terminate, so let's cancel.
  (cancel))


(tests
  "continuous flow (lazy signal with work-skipping)"
  (def !x (atom 0))
  ((m/ap (! (m/?< (m/watch !x))))
   (fn notify [] (println ::ready))
   (fn terminate [] (println ::done)))
  ; ::ready
  (swap! !x inc)
  (swap! !x inc)
  (swap! !x inc)
  % := 2)

(tests
  "integrate a discrete flow into a continuous flow by relieving backpressure"
  (def !x (atom 0))
  ((m/ap (! (m/relieve + ~(m/watch !x))))
   (fn n [])
   (fn t []))
  (swap! !x inc)
  (swap! !x inc)
  (swap! !x inc)
  % := 3)

(tests
  "dataflow diamond, an incremental DAG with shared node"
  (def a (atom 1))
  (def app
    (m/reactor
      (let [>a (m/signal! (m/watch a))
            >b (m/signal! (m/latest + >a >a))]
        (m/stream! (m/ap (! (m/?> >b)))))))
  (def dispose (app prn prn))
  % := 2
  (swap! a inc)
  % := 4
  ; A glitch-free engine emits 2 4, Rx would wrongly emit 2 3 4
  (dispose))

(tests
  "hiccup incremental rendering with work-skipping")

(comment
  "hiccup incremental view maintenance"

  (def ast (m/ap [:div [:div x] [:div :a]]))

  (def !x (atom 0))
  (def dispose
    (r/run (!

             (widget ~(m/watch !x))

             )))
  % := 0
  % := :a
  % := [[:div 0] [:div :a]]
  % := [:div [[:div 0] [:div :a]]]
  (swap! !x inc)
  % := 1
  ; no :a
  % := [[:div 1] [:div :a]]
  % := [:div [[:div 1] [:div :a]]]
  (dispose))

(comment
  "hiccup incremental view maintenance"

  (defnode !')
  (defnode div [child] (!' child) [:div child])
  (defnode widget [x]
           (div [(div x) (div :a)]))

  (def !x (atom 0))
  (def dispose
    (r/run (! (r/bind [(!' [x] (rcf/! x))]
                      (widget ~(m/watch !x))))))
  % := 0
  % := :a
  % := [[:div 0] [:div :a]]
  % := [:div [[:div 0] [:div :a]]]
  (swap! !x inc)
  % := 1
  ; no :a
  % := [[:div 1] [:div :a]]
  % := [:div [[:div 1] [:div :a]]]
  (dispose))