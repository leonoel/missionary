(ns ^:no-doc missionary.impl.latest
  (:require [missionary.impl.pairing-heap :as ph]
            [missionary.impl.varhandle :as v]
            [missionary.impl.contract :as c]
            [missionary.impl.util :as u])
  (:import (clojure.lang AFn IMeta IObj IFn IDeref)))

(def undefined (u/sentinel))

(declare cancel transfer)
(deftype Ps [step done combinator args inputs
             ^:unsynchronized-mutable state
             ^:unsynchronized-mutable owner
             ^:unsynchronized-mutable ready
             ^:unsynchronized-mutable head
             ^:unsynchronized-mutable tail
             ^:unsynchronized-mutable ^int sync
             ^:unsynchronized-mutable ^int alive]
  IFn (#?(:clj invoke :cljs -invoke) [ps] (cancel ps))
  IDeref (#?(:clj deref :cljs -deref) [ps] (transfer ps)))

(deftype Input [^int index
                ^:unsynchronized-mutable process
                ^:unsynchronized-mutable child
                ^:unsynchronized-mutable sibling])

(declare ready)

(defmacro lt [x y]
  `(let [^Input x# ~x
         ^Input y# ~y]
     (< (.-index x#) (.-index y#))))

(ph/defimpl impl
  :lt lt
  :ready ready
  :head Ps/head
  :tail Ps/tail
  :child Input/child
  :sibling Input/sibling)

(defn dequeue [x]
  (impl ph/dequeue x))

(defn accept [^Ps ps]
  (impl ph/accept ps))

(defn discard-heap [ps ready]
  (loop [ready ready]
    (v/set Ps/ready ps (dequeue ready))
    (u/discard (v/get Input/process ready))
    (when-some [ready (v/get Ps/ready ps)]
      (recur ready))))

(defn ready [^Ps ps]
  (loop []
    (when-not (zero? (v/get-and-bitwise-xor Ps/sync ps 1))
      (if (neg? (v/get Ps/alive ps))
        ((.-done ps))
        (if (identical? (v/get Ps/state ps) undefined)
          (do (v/set Ps/owner ps (u/thread))
              (discard-heap ps (accept ps))
              (v/set Ps/owner ps nil)
              (recur))
          ((.-step ps)))))))

(defn cancel [^Ps ps]
  (let [inputs (.-inputs ps)]
    (dotimes [i (alength inputs)]
      ((v/get Input/process (aget inputs i))))))

(defn check-init [^Ps ps]
  (let [args (.-args ps)]
    (dotimes [i (alength args)]
      (when (identical? (aget args i) undefined)
        (throw (u/error "Uninitialized continuous flow."))))))

(defn combine [^Ps ps]
  (let [state (u/call (.-combinator ps) (.-args ps))]
    (v/set Ps/state ps state)
    state))

(defn pull-ready [^Ps ps]
  (loop []
    (when-some [^Input ready (v/get Ps/ready ps)]
      (v/set Ps/ready ps (dequeue ready))
      (aset (.-args ps) (.-index ready) @(v/get Input/process ready))
      (recur))))

(defn transfer [^Ps ps]
  (v/set Ps/owner ps (u/thread))
  (try
    (let [state (v/get Ps/state ps)]
      (if (identical? state undefined)
        (do (pull-ready ps)
            (check-init ps)
            (combine ps))
        (loop [^Input ready (accept ps)]
          (v/set Ps/ready ps (dequeue ready))
          (if (= (aget (.-args ps) (.-index ready))
                (aset (.-args ps) (.-index ready)
                  @(v/get Input/process ready)))
            (if-some [ready (v/get Ps/ready ps)]
              (recur ready) state)
            (do (pull-ready ps)
                (combine ps))))))
    (catch #?(:clj Throwable :cljs :default) e
      (v/set Ps/state ps undefined)
      (cancel ps)
      (when-some [ready (v/get Ps/ready ps)]
        (discard-heap ps ready))
      (throw e))
    (finally
      (v/access :set (v/object-field Ps/owner) ps nil)
      (ready ps))))

(defn spawn-input [^Ps ps i flow]
  (let [input (->Input i nil nil nil)
        step #(if (identical? (u/thread) (v/get Ps/owner ps))
                (v/set Ps/ready ps
                  (impl ph/enqueue (v/get Ps/ready ps) input))
                (impl ph/insert ps input))
        done (v/get Ps/state ps)]
    (v/set Input/process input (flow step done))
    (aset (.-args ps) i undefined)
    (aset (.-inputs ps) i input) ps))

(deftype Effect [combinator inputs]
  IMeta
  (#?(:clj meta :cljs -meta) [_] c/effect-flow)
  #?(:clj IObj :cljs IWithMeta)
  (#?(:clj withMeta :cljs -with-meta) [this meta]
    (u/meta-holder this meta))
  IFn
  (#?(:clj invoke :cljs -invoke) [_ step done]
    (let [arity (count inputs)
          ps (->Ps step done combinator
               (u/object-array arity)
               (u/object-array arity)
               nil nil nil nil nil 0 arity)
          done #(when (zero? (v/get-and-add Ps/alive ps -1))
                  (ready ps))]
      (impl ph/init ps)
      (v/set Ps/state ps done)
      (v/set Ps/owner ps (u/thread))
      (reduce-kv spawn-input ps inputs)
      (v/set Ps/owner ps nil)
      (v/set Ps/state ps undefined)
      (step) (done) ps))
  #?(:clj
     (applyTo [this args]
       (AFn/applyToHelper this args))))
