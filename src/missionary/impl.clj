(ns ^:no-doc missionary.impl
  (:import (java.io Writer)
           (org.reactivestreams Publisher)
           (missionary.impl
             Enumerate Aggregate RaceJoin Sleep Never Ambiguous Fiber Thunk Dataflow Mailbox Rendezvous Semaphore
             Watch Observe Transform Integrate Pub Sub Relieve Buffer Latest Sample Zip Sequential Ambiguous$Process
             Reactor Reactor$Context Reactor$Publisher)))

(defn nop [])

(def blk Thunk/blk)
(def cpu Thunk/cpu)

(defn absolver [s f]
  (fn [t] (try (s (t)) (catch Throwable e (f e)))))

(defn print-object [o ^Writer w]
  (.write w "#object[")
  (.write w (.getName (class o)))
  (.write w " ")
  (.write w (format "0x%x" (System/identityHashCode o)))
  (.write w "]"))

(defmethod print-method Never [o w] (print-object o w))
(defn never [f] (Never. f))

(defmethod print-method Thunk [o w] (print-object o w))
(defn thunk [e t s f] (Thunk. e t s f))

(defmethod print-method Sequential [o w] (print-object o w))
(defn sp [c s f] (Sequential. c s f))

(defmethod print-method Ambiguous$Process [o w] (print-object o w))
(defn ap [c n t] (Ambiguous/process c n t))

(defn fiber-unpark      []  (.unpark     ^Fiber (.get Fiber/CURRENT)))
(defn fiber-poll        []  (.poll       ^Fiber (.get Fiber/CURRENT)))
(defn fiber-task        [t] (.task       ^Fiber (.get Fiber/CURRENT) t))
(defn fiber-flow-concat [f] (.flowConcat ^Fiber (.get Fiber/CURRENT) f))
(defn fiber-flow-switch [f] (.flowSwitch ^Fiber (.get Fiber/CURRENT) f))
(defn fiber-flow-gather [f] (.flowGather ^Fiber (.get Fiber/CURRENT) f))

(defmethod print-method RaceJoin [o w] (print-object o w))
(defn race-join [r c ts s f] (RaceJoin. r c ts s f))

(defmethod print-method Sleep [o w] (print-object o w))
(defn sleep [d x s f] (Sleep. d x s f))

(defmethod print-method Dataflow [o w] (print-object o w))
(defn dataflow [] (Dataflow.))

(defmethod print-method Mailbox [o w] (print-object o w))
(defn mailbox [] (Mailbox.))

(defmethod print-method Rendezvous [o w] (print-object o w))
(defn rendezvous [] (Rendezvous.))

(defmethod print-method Semaphore [o w] (print-object o w))
(defn semaphore [n] (Semaphore. n))

(defmethod print-method Enumerate [o w] (print-object o w))
(defn enumerate [c n t] (Enumerate. c n t))

(defmethod print-method Aggregate [o w] (print-object o w))
(defn aggregate [rf i flow s f] (Aggregate. rf i flow s f))

(defmethod print-method Watch [o w] (print-object o w))
(defn watch [r n t] (Watch. r n t))

(defmethod print-method Observe [o w] (print-object o w))
(defn observe [s n t] (Observe. s n t))

(defmethod print-method Transform [o w] (print-object o w))
(defn transform [x f n t] (Transform. x f n t))

(defmethod print-method Integrate [o w] (print-object o w))
(defn integrate [rf i f n t] (Integrate. rf i f n t))

(defmethod print-method Relieve [o w] (print-object o w))
(defn relieve [rf f n t] (Relieve. rf f n t))

(defmethod print-method Buffer [o w] (print-object o w))
(defn buffer [c f n t] (Buffer. c f n t))

(defmethod print-method Latest [o w] (print-object o w))
(defn latest [f fs n t] (Latest. f fs n t))

(defmethod print-method Sample [o w] (print-object o w))
(defn sample [f sd sr n t] (Sample. f sd sr n t))

(defmethod print-method Zip [o w] (print-object o w))
(defn zip [c fs n t] (Zip. c fs n t))

(defmethod print-method Sub [o w] (print-object o w))
(defn subscribe [pub n t] (Sub. pub n t))
(defn publisher [f] (reify Publisher (subscribe [_ s] (Pub. f s))))

(defmethod print-method Reactor$Context [o w] (print-object o w))
(defn context [i s f] (Reactor/context i s f))

(defmethod print-method Reactor$Publisher [o w] (print-object o w))
(defn publish [f d] (Reactor/publish f d))