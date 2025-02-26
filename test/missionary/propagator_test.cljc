(ns missionary.propagator-test
  (:require [lolcat.lib :as l]
            [lolcat.core :as lc]
            [clojure.test :as t]
            [missionary.core :as m])
  (:import missionary.Cancelled))

(def error (ex-info "" {}))

(lc/defword succeed [id result & events]
  [(l/lookup id) false (l/swap) result (l/swap)
   (apply lc/call 2 events) (l/lose)])

(lc/defword fail [id result & events]
  [(l/lookup id) true (l/swap) result (l/swap)
   (apply lc/call 2 events) (l/lose)])

(lc/defword sub [pub-id sub-id & insts]
  [(l/lookup pub-id) (apply l/perform sub-id insts)])

(t/deftest memo-success
  (l/run
    (m/memo l/effect) (l/insert :memo)
    (sub :memo :sub1
      (l/performed :target))
    (succeed :target :foo
      (l/succeeded :sub1 #{:foo}))
    (l/cancel :sub1)
    (sub :memo :sub2
      (l/succeeded :sub2 #{:foo}))
    (l/cancel :sub2)))

(t/deftest memo-failure
  (l/run
    (m/memo l/effect) (l/insert :memo)
    (sub :memo :sub1
      (l/performed :target))
    (fail :target error
      (l/failed :sub1 #{error}))
    (l/cancel :sub1)
    (sub :memo :sub2
      (l/failed :sub2 #{error}))
    (l/cancel :sub2)))

(t/deftest memo-cancel
  (l/run
    (m/memo l/effect) (l/insert :memo)
    (sub :memo :sub1
      (l/performed :target1))
    (sub :memo :sub2)
    (l/cancel :sub1
      (l/failed :sub1 #(instance? Cancelled %)))
    (l/cancel :sub2
      (l/cancelled :target1))
    (sub :memo :sub3
      (l/performed :target2))
    (fail :target1 error
      (l/failed :sub2 #{error}))
    (succeed :target2 :foo
      (l/succeeded :sub3 #{:foo}))))

(t/deftest signal-constant
  (l/run
    (m/signal l/effect) (l/insert :signal)
    (sub :signal :sub
      (l/performed :target
        (l/notify :target))
      (l/notified :sub))
    (l/transfer :sub
      (l/transferred :target
        (l/terminate :target)
        :foo)
      (l/terminated :sub))
    (l/check #{:foo})))

(t/deftest signal-diamond
  (l/run
    (let [s (m/signal l/effect)]
      (m/signal (m/latest vector s s)))
    (l/perform :main
      (l/performed :input
        (l/notify :input))
      (l/notified :main))
    (l/transfer :main
      (l/transferred :input 0))
    (l/check #{[0 0]})
    (l/notify :input
      (l/notified :main
        (l/transfer :main
          (l/transferred :input 1))
        (l/check #{[1 1]})))
    (l/terminate :input
      (l/terminated :main))))

(t/deftest signal-cancel
  (l/run
    (m/signal l/effect)
    (l/perform :main
      (l/performed :input
        (l/notify :input))
      (l/notified :main))
    (l/transfer :main
      (l/transferred :input 0))
    (l/check #{0})
    (l/cancel :main
      (l/cancelled :input
        (l/notify :input))
      (l/notified :main))
    (l/crash :main
      (l/crashed :input
        (l/terminate :input)
        error)
      (l/terminated :main))
    (l/check #{error})
    (l/cancel :main)))

(t/deftest signal-plus
  (l/run
    (m/signal + l/effect)
    (l/insert :signal)
    (sub :signal :sub1
      (l/performed :input
        (l/notify :input))
      (l/notified :sub1))
    (l/transfer :sub1
      (l/transferred :input 0))
    (l/check #{0})
    (l/notify :input
      (l/notified :sub1))
    (l/transfer :sub1
      (l/transferred :input 2))
    (l/check #{2})
    (l/notify :input
      (l/notified :sub1))
    (sub :signal :sub2
      (l/notified :sub2))
    (l/transfer :sub2
      (l/transferred :input 3))
    (l/check #{5})
    (l/transfer :sub1)
    (l/check #{3})))

(t/deftest stream-one
  (l/run
    (m/stream l/effect)
    (l/perform :main
      (l/performed :input
        (l/notify :input))
      (l/notified :main))
    (l/transfer :main
      (l/transferred :input
        (l/notify :input) 0)
      (l/notified :main))
    (l/check #{0})
    (l/transfer :main
      (l/transferred :input
        (l/notify :input) 1)
      (l/notified :main))
    (l/check #{1})
    (l/transfer :main
      (l/transferred :input
        (l/terminate :input) 2)
      (l/terminated :main))
    (l/check #{2})
    (l/cancel :main)))

(t/deftest stream-one-cancel
  (l/run
    (m/stream l/effect)
    (l/perform :main
      (l/performed :input
        (l/notify :input))
      (l/notified :main))
    (l/transfer :main
      (l/transferred :input 0))
    (l/check #{0})
    (l/cancel :main
      (l/cancelled :input
        (l/notify :input))
      (l/notified :main))
    (l/crash :main
      (l/crashed :input
        (l/terminate :input)
        error)
      (l/terminated :main))
    (l/check #{error})
    (l/cancel :main)))

(t/deftest stream-diamond
  (let [sub (m/stream l/effect)]
    (l/run
      (m/stream l/effect)
      (l/perform :main
        (l/performed :input
          sub
          (l/perform :sub1
            (l/performed :pub
              (l/notify :pub))
            (l/notified :sub1))
          (l/transfer :sub1
            (l/transferred :pub
              (l/notify :pub) 0))
          (l/check #{0})
          sub
          (l/perform :sub2
            (l/notified :sub2))
          (l/transfer :sub2)
          (l/check #{0})
          (l/notify :input))
        (l/notified :main)
        (l/notified :sub2)
        (l/notified :sub1))
      (l/transfer :main
        (l/transferred :input
          (l/transfer :sub1
            (l/transferred :pub
              (l/notify :pub) 1))
          (l/check #{1})
          (l/transfer :sub2)
          (l/check #{1})
          (l/notify :input)
          :a)
        (l/notified :sub2)
        (l/notified :sub1)
        (l/notified :main))
      (l/check #{:a})
      (l/transfer :main
        (l/transferred :input
          (l/transfer :sub1
            (l/transferred :pub
              (l/notify :pub) 2))
          (l/check #{2})
          (l/transfer :sub2)
          (l/check #{2})
          (l/notify :input)
          :b)
        (l/notified :sub2)
        (l/notified :sub1)
        (l/notified :main))
      (l/check #{:b}))))

(t/deftest stream-two-subs
  (let [sub (m/stream l/effect)]
    (l/run
      sub
      (l/perform :sub1
        (l/performed :input))
      sub
      (l/perform :sub2)
      (l/notify :input
        (l/notified :sub2)
        (l/notified :sub1))
      (l/transfer :sub1
        (l/transferred :input
          (l/notify :input) 0))
      (l/check #{0})
      (l/transfer :sub2
        (l/notified :sub2)
        (l/notified :sub1))
      (l/check #{0}))))

(t/deftest signal-cancel-while-scheduled
  (l/run
    (m/signal l/effect)
    (l/perform :sub1
      (l/performed :eff1
        (l/notify :eff1))
      (l/notified :sub1))
    (l/transfer :sub1
      (l/transferred :eff1 1))
    (l/check #{1})
    (m/signal l/effect)
    (l/perform :sub2
      (l/performed :eff2
        (l/notify :eff2))
      (l/notified :sub2))
    (l/transfer :sub2
      (l/transferred :eff2 2))
    (l/check #{2})
    (l/cancel :sub2
      (l/cancelled :eff2
        (l/notify :eff1)
        (l/cancel :sub1
          (l/cancelled :eff1)))
      (l/notified :sub1))))        ;; TBD : should step be synchronous with cancellation ?

(t/deftest no-reentrant-propagation
  (let [x (m/signal l/effect)
        y (m/signal l/effect)]
    (l/run
      x (l/perform :x-sub
          (l/performed :x-ps
            (l/notify :x-ps))
          (l/notified :x-sub))
      y (l/perform :y-sub
          (l/performed :y-ps
            (l/notify :y-ps))
          (l/notified :y-sub))
      (l/transfer :x-sub
        (l/transferred :x-ps :x1))
      (l/check #{:x1})
      (l/transfer :y-sub
        (l/transferred :y-ps :y1))
      (l/check #{:y1})
      (l/notify :y-ps
        (l/notified :y-sub
          (l/transfer :y-sub
            (l/transferred :y-ps :y2))
          (l/check #{:y2})
          (l/notify :x-ps))
        (l/notified :x-sub
          (l/transfer :x-sub
            (l/transferred :x-ps :x2))
          (l/check #{:x2}))))))

(t/deftest uninitialized-signal
  (l/run
    (m/signal l/effect)
    (l/perform :sub
      (l/performed :eff)
      (l/cancelled :eff
        (l/notify :eff))
      (l/notified :sub)
      (l/crashed :eff
        (l/terminate :eff)
        (Cancelled.)))
    (l/crash :sub
      (l/terminated :sub))
    (lc/drop 1)))

(t/deftest empty-signal
  (l/run
    (m/signal l/effect)
    (l/perform :sub
      (l/performed :eff
        (l/terminate :eff))
      (l/cancelled :eff)
      (l/notified :sub))
    (l/crash :sub
      (l/terminated :sub))
    (lc/drop 1)))

(t/deftest stream-subscribe-after-termination
  (let [s (m/stream (m/seed (range 10)))
        t (m/reduce conj s)]
    (l/run
      t (l/perform :sub1 (l/succeeded :sub1 #{(range 10)}))
      t (l/perform :sub2 (l/succeeded :sub2 #{()})))))

(t/deftest signal-unsub-after-termination-while-pending
  (let [s (m/signal l/effect)]
    (l/run
      s (l/perform :sub1
          (l/performed :eff
            (l/notify :eff))
          (l/notified :sub1))
      s (l/perform :sub2
          (l/notified :sub2))
      (l/transfer :sub2
        (l/transferred :eff
          (l/terminate :eff)
          :foo)
        (l/terminated :sub2))
      (l/check #{:foo})
      (l/cancel :sub1)
      (l/transfer :sub1
        (l/terminated :sub1))
      (l/check #{:foo}))))