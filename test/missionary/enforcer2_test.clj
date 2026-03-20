(ns missionary.enforcer2-test
  (:require [clojure.test :refer [deftest is testing]]
            [missionary.flow-protocol-enforcer2 :as e2])
  (:import [clojure.lang IDeref IFn]
           [missionary ProtocolViolation]))

(defn- violations-callback
  "Returns [callback, violations-atom]. callback appends to atom."
  []
  (let [vs (atom [])]
    [(fn [e] (swap! vs conj e)) vs]))

(defn- test-flow "steps once, returns value on transfer, calls done on cancel." [value]
  (fn [step done]
    (step)
    (let [cancelled (atom false)]
      (reify
        IDeref (deref [_] (if @cancelled (throw (new missionary.Cancelled)) value))
        IFn (invoke [_] (reset! cancelled true) (done))))))

;; ── S4: make-violation ──────────────────────────────────────────

(deftest make-violation-constructs-without-throwing
  (let [[cb vs] (violations-callback)]
    ((e2/flow cb :test (test-flow 42)) #() #())
    (is (= [] @vs) "good flow produces no violations")))

;; ── S3/step: step violations ────────────────────────────────────

(deftest double-step-detected-and-forwarded
  (let [[cb vs] (violations-callback)
        consumer-steps (atom 0)
        f (fn [step _]
            (step) (step) ;; second step is violation
            (reify IDeref (deref [_] :v) IFn (invoke [_])))]
    ((e2/flow cb :test f) #(swap! consumer-steps inc) #())
    (is (= 1 (count @vs)))
    (is (instance? ProtocolViolation (first @vs)))
    (is (re-find #"double step" (ex-message (first @vs))))
    (is (= 2 @consumer-steps) "both steps forwarded to consumer")))

(deftest step-after-done-detected-and-forwarded
  (let [[cb vs] (violations-callback)
        step-fn (atom nil) done-fn (atom nil)
        consumer-steps (atom 0)
        f (fn [step done]
            (reset! step-fn step) (reset! done-fn done)
            (step)
            (reify IDeref (deref [_] :v) IFn (invoke [_])))]
    (let [it ((e2/flow cb :test f) #(swap! consumer-steps inc) #())]
      @it           ;; valid transfer
      (@done-fn)    ;; valid done
      (@step-fn)    ;; violation: step after done
      (is (= 1 (count @vs)))
      (is (re-find #"step after done" (ex-message (first @vs))))
      (is (= 2 @consumer-steps) "initial step + violation step both forwarded"))))

(deftest step-after-crash-detected-and-forwarded
  (let [[cb vs] (violations-callback)
        error (ex-info "boom" {})
        step-fn (atom nil)
        consumer-steps (atom 0)
        f (fn [step _]
            (reset! step-fn step)
            (step)
            (reify IDeref (deref [_] (throw error)) IFn (invoke [_])))
        it ((e2/flow cb :test f) #(swap! consumer-steps inc) #())]
    (is (thrown? Exception @it))
    ;; child calls step after crash
    (@step-fn)
    (is (= 1 (count @vs)))
    (is (re-find #"step after crash" (ex-message (first @vs))))
    (is (= 2 @consumer-steps) "step forwarded despite violation")))

(deftest step-cannot-throw-detected-and-rethrown
  (let [[cb vs] (violations-callback)
        error (ex-info "consumer-step-threw" {})
        consumer-step (fn [] (throw error))
        f (fn [step _]
            (step) ;; this calls consumer-step which throws
            (reify IDeref (deref [_] :v) IFn (invoke [_])))]
    ;; consumer-step throws during (input-flow step' done') → step' re-throws →
    ;; caught by constructor try-catch → on-violation("flow process creation threw") + re-throw
    (is (thrown? Exception
          ((e2/flow cb :test f) consumer-step #())))
    (is (>= (count @vs) 1))
    (is (some #(re-find #"step cannot throw" (ex-message %)) @vs))))

;; ── S3/done: done violations ────────────────────────────────────

(deftest done-after-step-without-transfer-detected
  (let [[cb vs] (violations-callback)
        consumer-dones (atom 0)
        f (fn [step done]
            (step)
            (done) ;; violation: stepped but not transferred
            (reify IDeref (deref [_] :v) IFn (invoke [_])))]
    ((e2/flow cb :test f) #() #(swap! consumer-dones inc))
    (is (= 1 (count @vs)))
    (is (re-find #"done after step without transfer" (ex-message (first @vs))))
    (is (= 1 @consumer-dones) "done forwarded despite violation")))

(deftest done-called-twice-detected
  (let [[cb vs] (violations-callback)
        consumer-dones (atom 0)
        f (fn [_ done]
            (done) (done)
            (reify IDeref (deref [_] :v) IFn (invoke [_])))]
    ((e2/flow cb :test f) #() #(swap! consumer-dones inc))
    (is (>= (count @vs) 1))
    (is (some #(re-find #"done called twice" (ex-message %)) @vs))
    (is (= 2 @consumer-dones) "both dones forwarded")))

(deftest done-cannot-throw-detected-and-rethrown
  (let [[cb vs] (violations-callback)
        error (ex-info "consumer-done-threw" {})
        consumer-done (fn [] (throw error))
        done-fn (atom nil)
        f (fn [step done]
            (reset! done-fn done)
            (step)
            (reify IDeref (deref [_] :v) IFn (invoke [_])))
        it ((e2/flow cb :test f) #() consumer-done)]
    @it ;; valid transfer (so done doesn't trigger "done after step without transfer")
    ;; trigger done from child — re-throws consumer-done exception
    (is (thrown? Exception (@done-fn)))
    (is (= 1 (count @vs)))
    (is (re-find #"done cannot throw" (ex-message (first @vs))))))

;; ── S3/deref: transfer violations ───────────────────────────────

(deftest transfer-after-crash-detected-and-forwarded
  (let [[cb vs] (violations-callback)
        error (ex-info "boom" {})
        f (fn [step _]
            (step)
            (reify IDeref (deref [_] (throw error)) IFn (invoke [_])))
        it ((e2/flow cb :test f) #() #())]
    ;; first transfer → crash
    (is (thrown? Exception @it))
    ;; second transfer → violation (transfer after crash) + forwarded
    (is (thrown? Exception @it))
    (is (= 1 (count @vs)))
    (is (re-find #"transfer after crash" (ex-message (first @vs))))))

(deftest double-transfer-detected-and-forwarded
  (let [[cb vs] (violations-callback)
        f (fn [step _]
            (step)
            (reify IDeref (deref [_] :v) IFn (invoke [_])))
        it ((e2/flow cb :test f) #() #())]
    ;; first transfer: valid
    (is (= :v @it))
    ;; second transfer without intervening step: violation
    (is (= :v @it))
    (is (= 1 (count @vs)))
    (is (re-find #"double transfer" (ex-message (first @vs))))))

;; ── S3/cancel: cancel cannot throw ──────────────────────────────

(deftest cancel-cannot-throw-detected-and-rethrown
  (let [[cb vs] (violations-callback)
        error (ex-info "cancel-threw" {})
        f (fn [step _]
            (step)
            (reify IDeref (deref [_] :v) IFn (invoke [_] (throw error))))
        it ((e2/flow cb :test f) #() #())]
    (is (thrown? Exception (it)))
    (is (= 1 (count @vs)))
    (is (re-find #"cancel cannot throw" (ex-message (first @vs))))))

;; ── S3/constructor: creation violations ─────────────────────────

(deftest flow-creation-threw-detected
  (let [[cb vs] (violations-callback)
        error (ex-info "creation-threw" {})]
    (is (thrown? Exception
          ((e2/flow cb :test (fn [_ _] (throw error))) #() #())))
    (is (= 1 (count @vs)))
    (is (re-find #"flow process creation threw" (ex-message (first @vs))))))

(deftest missing-initial-step-detected
  (let [[cb vs] (violations-callback)
        f (fn [_ _]
            ;; no step call (default enforcer expects initial step)
            (reify IDeref (deref [_] :v) IFn (invoke [_])))]
    ((e2/flow cb :test f) #() #())
    (is (= 1 (count @vs)))
    (is (re-find #"missing initial step" (ex-message (first @vs))))))

;; ── C1: pure pass-through ───────────────────────────────────────

(deftest happy-path-is-pass-through
  (let [[cb vs] (violations-callback)
        steps (atom 0)
        dones (atom 0)
        f (fn [step done]
            (step)
            (reify
              IDeref (deref [_] 42)
              IFn (invoke [_] (done))))
        it ((e2/flow cb :test f)
            #(swap! steps inc)
            #(swap! dones inc))]
    (is (= 1 @steps) "consumer step called once")
    (is (= 42 @it) "transfer returns value")
    (it) ;; cancel → triggers done
    (is (= 1 @dones) "consumer done called once")
    (is (= [] @vs) "no violations")))

;; ── C4: state not mutated on violation ──────────────────────────

(deftest state-preserved-after-violation
  (testing "double step doesn't corrupt state — subsequent transfer still works"
    (let [[cb vs] (violations-callback)
          step-fn (atom nil)
          f (fn [step _]
              (reset! step-fn step)
              (step)
              (reify IDeref (deref [_] :v) IFn (invoke [_])))
          it ((e2/flow cb :test f) #() #())]
      ;; valid transfer
      (is (= :v @it))
      ;; valid step
      (@step-fn)
      ;; double step — violation, state preserved
      (@step-fn)
      (is (= 1 (count @vs)))
      ;; transfer should still work (state wasn't corrupted)
      (is (= :v @it)))))
