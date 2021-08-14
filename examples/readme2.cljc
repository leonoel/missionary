(ns readme2
  (:require [missionary.core :as m]
            [hyperfiddle.rcf :as rcf :refer [tests ! %]]))

(tests
  "async tests demo"
  ; rcf/! is like println except taps the value to a queue for checking
  (rcf/! :a)
  (! :b)
  % := :a
  % := :b
  % := ::rcf/timeout)

(tests
  "Task is a single-value producer (one-off async effect)"
  (def task (m/sp (println "Hello") (m/? (m/sleep 50)) (! 42)))
  (def cancel! (task (fn success [v] (println :success v))
                     (fn failure [e] (println :failure e))))
  % := 42)

(tests
  "Tasks are sequential composition (monad-like, async/await)"
  (def nap (m/sleep 10 42))
  (def task
    (m/sp
      (m/? (if (odd? (m/? nap))     ; runtime control flow
             (m/sleep 10 ::odd)
             (m/sleep 10 ::even)))))
  (def cancel! (task ! !))
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
  ((m/ap (! (m/?< (m/watch !x))))
   (fn ready [] (println ::ready))
   (fn done [] (println ::done)))
  ; ::ready
  (swap! !x inc)
  (swap! !x inc)
  % := 0
  % := 1
  % := 2)

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