(ns missionary.propagator-crash-test
  (:require [clojure.test :refer [deftest is]]
            [missionary.core :as m]
            [missionary.flow-protocol-enforcer :as enforcer])
  (:import [clojure.lang IDeref IFn]
           missionary.ProtocolViolation))

;; Propagator.unsubscribe calls step on a subscription after its transfer threw.
;; Requires 2+ subscribers (single-subscriber unsub takes a fast path that skips unsubscribe).
;; source → signal → 2 enforced arms → latest. Source crash → Latest cancels arms →
;; unsub → unsubscribe calls step on crashed arm.
;;
;; Runs on a separate thread because the ProtocolViolation escapes from inside
;; Propagator.unsubscribe without leave() being called, corrupting the thread-local
;; Propagator context. Isolating to a fresh thread prevents this from affecting other tests.
(deftest signal-diamond-step-after-crash
  (let [result @(future
                  (let [error   (ex-info "boom" {})
                        !step   (volatile! nil)
                        !throw? (volatile! false)
                        n       (atom 0)
                        source  (fn [step _done]
                                  (vreset! !step step)
                                  (step)
                                  (reify
                                    IDeref (deref [_] (if @!throw? (throw error) (swap! n inc)))
                                    IFn    (invoke [_] nil)))
                        sig     (m/signal source)
                        a0      (enforcer/flow :arm-0 sig)
                        a1      (enforcer/flow :arm-1 sig)
                        it      ((m/latest vector a0 a1) #() #())]
                    (vreset! !throw? true)
                    (@!step)
                    (try @it :no-violation
                         (catch ProtocolViolation _ :violation))))]
    (is (= :violation result))))
