(ns ^:no-doc missionary.impl
  (:import
    (java.io Writer)
    (org.reactivestreams Publisher)
    (missionary.impl
      Enumerate RaceJoin Sleep Never Thunk Dataflow Mailbox Rendezvous Semaphore
      Transform Pub Sub Buffer Zip)))

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

(defmethod print-method Transform [o w] (print-object o w))
(defn transform [x f n t] (Transform. x f n t))

(defmethod print-method Buffer [o w] (print-object o w))
(defn buffer [c f n t] (Buffer. c f n t))

(defmethod print-method Zip [o w] (print-object o w))
(defn zip [c fs n t] (Zip. c fs n t))

(defmethod print-method Sub [o w] (print-object o w))
(defn subscribe [pub n t] (Sub. pub n t))
(defn publisher [f] (reify Publisher (subscribe [_ s] (Pub. f s))))
