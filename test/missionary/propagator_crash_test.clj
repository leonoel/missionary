(ns missionary.propagator-crash-test
  (:require [clojure.test :refer [deftest is]]
            [missionary.core :as m]
            [missionary.flow-protocol-enforcer2 :as enforcer])
  (:import [clojure.lang IDeref IFn]))

(deftest signal-diamond-step-after-crash
  (let [[result violations]
        @(future
           (let [error   (ex-info "boom" {})
                 !step   (volatile! nil)
                 !throw? (volatile! false)
                 n       (atom 0)
                 !violations (atom [])
                 on-violated (partial swap! !violations conj)
                 source  (fn [step _done]
                           (vreset! !step step)
                           (step)
                           (reify
                             IDeref (deref [_] (if @!throw? (throw error) (swap! n inc)))
                             IFn    (invoke [_] nil)))
                 sig     (enforcer/flow on-violated :signal
                           (m/signal (enforcer/flow on-violated :source source)))
                 it      ((m/signal (m/latest vector sig sig)) #() #())]
             (vreset! !throw? true)
             [(try @it (catch Throwable e e)) @!violations]))]
    (is (= [] violations))
    (is (= "boom" (ex-message result)))))
