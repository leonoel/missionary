(ns ^:no-doc missionary.impl.Ambiguous
  (:require [missionary.impl.Fiber :refer [Fiber fiber]])
  (:import missionary.Cancelled))

(declare kill transfer suspend resume ready walk)

(deftype Process [notifier terminator head tail child]
  IFn
  (-invoke [this] (kill this))
  IDeref
  (-deref [this] (transfer this)))

(deftype Branch [parent prev next queue choice current]
  Fiber
  (check [_] (when-not (.-live choice) (throw (Cancelled. "Process cancelled."))))
  (park [this task] (suspend this nil task))
  (swich [this flow] (suspend this -1 flow))
  (fork [this par flow] (suspend this par flow))
  (unpark [this] (resume this)))

(deftype Choice [branch prev next coroutine iterator ^number parallelism ^boolean live ^boolean busy ^boolean done])
(deftype Processor [branch prev next child])

(defn nop [])

(defn boot [cr ^Choice c]
  (set! (.-coroutine c) cr)
  (ready c))

(defn backtrack [^Choice p ^Branch b ^Choice c]
  (try (set! (.-iterator c) nop)
       (set! (.-choice b) c)
       (set! (.-current b) @(.-iterator p))
       ((.-coroutine p) boot c)
       (catch :default e
         (set! (.-done c) true)
         (set! (.-current b) e)
         (boot (.-coroutine p) c))))

(defn choose [^Choice p]
  (let [b (.-branch p)
        n (.-next p)]
    (->> (->Choice b p n nil nil nil (.-live p) false false)
      (set! (.-next p))
      (set! (.-prev n))
      (backtrack p b))))

(defn branch [^Choice p]
  (set! (.-parallelism p) (dec (.-parallelism p)))
  (let [parent (.-branch p)
        prev (.-current parent)
        curr (->Processor parent nil nil nil)
        b (->Branch curr nil nil nil nil nil)
        c (->Choice b nil nil nil nil nil (.-live p) false false)]
    (set! (.-current parent) curr)
    (if (nil? prev)
      (->> curr
        (set! (.-prev curr))
        (set! (.-next curr)))
      (let [next (.-next prev)]
        (->> curr
          (set! (.-prev next))
          (set! (.-next prev)))
        (set! (.-prev curr) prev)
        (set! (.-next curr) next)))
    (->> b
      (set! (.-prev b))
      (set! (.-next b))
      (set! (.-child curr)))
    (->> c
      (set! (.-prev c))
      (set! (.-next c))
      (backtrack p b))))

(defn root [^Branch b]
  (loop [node (.-parent b)]
    (if (instance? Processor node)
      (recur (.-parent (.-branch ^Processor node)))
      (if (instance? Branch node)
        (recur (.-parent ^Branch node))
        node))))

(defn kill [^Process ps]
  (when-some [b (.-child ps)]
    (walk (.-next b))))

(defn cancel [^Choice c]
  (let [b (.-branch c)]
    (loop [c c]
      (when (.-live c)
        (set! (.-live c) false)
        ((.-iterator c))
        (when-some [t (.-choice b)]
          (let [c (loop [c c]
                    (let [c (.-next c)]
                      (if (nil? (.-prev c))
                        (recur c) c)))]
            (if (identical? c (.-next t))
              (let [curr (.-current b)]
                (if (instance? Processor curr)
                  (loop [pr ^Processor curr]
                    (walk (.-next (.-child pr)))
                    (when-some [curr (.-current b)]
                      (let [pr (loop [pr pr]
                                 (let [pr (.-next pr)]
                                   (if (nil? (.-prev pr))
                                     (recur pr) pr)))]
                        (when-not (identical? pr (.-next ^Processor curr))
                          (recur pr)))))
                  (when (instance? Branch curr)
                    (walk (.-next ^Branch curr)))))
              (recur c))))))))

(defn walk [^Branch b]
  (loop [b b]
    (cancel (.-next (.-choice b)))
    (let [node (.-parent b)]
      (when-some [t (if (instance? Processor node)
                      (.-child ^Processor node)
                      (if (instance? Branch node)
                        (.-current ^Branch node)
                        (.-child ^Process node)))]
        (let [b (loop [b b]
                  (let [b (.-next b)]
                    (if (nil? (.-prev b))
                      (recur b) b)))]
          (when-not (identical? b (.-next t))
            (recur b)))))))

(defn move [^Branch x ^Branch y]
  (let [p (.-parent x)]
    (loop [b y]
      (let [b (.-next b)]
        (set! (.-parent b) p)
        (when-not (identical? b y)
          (recur b)))))
  (let [xx (.-next x)
        yy (.-next y)]
    (set! (.-next x) yy)
    (set! (.-prev yy) x)
    (set! (.-next y) xx)
    (set! (.-prev xx) y)))

(defn discard [^Branch b]
  (let [parent (.-parent b)
        prev (.-prev b)
        next (.-next b)]
    (set! (.-prev b) nil)
    (set! (.-choice b) nil)
    (set! (.-current b) nil)
    (if (instance? Branch parent)
      (let [br ^Branch parent]
        (if (identical? b prev)
          (let [c (.-choice br)]
            (set! (.-current b) nil)
            (when (.-busy c)
              (when (.-done c)
                (set! (.-busy c) false)
                (set! (.-done c) false)
                (choose c))))
          (do (set! (.-next prev) next)
              (set! (.-prev next) prev)
              (when (identical? b (.-current br))
                (set! (.-current br) prev)))))
      (if (instance? Processor parent)
        (let [pr ^Processor parent]
          (if (identical? b prev)
            (let [b (.-branch pr)
                  c (.-choice b)
                  p (.-prev pr)
                  n (.-next pr)]
              (set! (.-child pr) nil)
              (set! (.-prev pr) nil)
              (if (identical? pr p)
                (set! (.-current b) nil)
                (do (set! (.-next p) n)
                    (set! (.-prev n) p)
                    (set! (.-current b) p)))
              (set! (.-parallelism c) (inc (.-parallelism c)))
              (when (.-busy c)
                (when (.-done c)
                  (set! (.-busy c) false)
                  (set! (.-done c) false)
                  (branch c))))
            (do (set! (.-next prev) next)
                (set! (.-prev next) prev)
                (when (identical? b (.-child pr))
                  (set! (.-child pr) prev)))))
        (let [ps ^Process parent]
          (if (identical? b prev)
            (do (set! (.-child ps) nil)
                ((.-terminator ps)))
            (do (set! (.-next prev) next)
                (set! (.-prev next) prev)
                (when (identical? b (.-child ps))
                  (set! (.-child ps) prev)))))))))

(defn ack [^Choice c]
  (when (.-busy c)
    (when (.-done c)
      (set! (.-busy c) false)
      (set! (.-done c) false)
      (if (neg? (.-parallelism c))
        (choose c) (branch c)))))

(defn done [^Branch b]
  (let [q (.-queue b)
        c (.-choice b)
        p (.-prev c)]
    (set! (.-prev c) nil)
    (set! (.-queue b) nil)
    (if (identical? p c)
      (discard b)
      (let [n (.-next c)]
        (set! (.-prev n) p)
        (set! (.-next p) n)
        (set! (.-choice b) p)
        (set! (.-current b) nil)
        (ack p))) q))

(defn transfer [^Process ps]
  (let [b (.-head ps)
        x (.-current b)]
    (when (.-done (.-choice b))
      (set! (.-notifier ps) nil)
      (kill ps)
      (loop [b (done b)]
        (when-not (nil? b)
          (recur (done b))))
      (loop [b (.-tail ps)]
        (when-not (nil? b)
          (recur (done b))))
      (set! (.-head ps) nil)
      (set! (.-tail ps) nil)
      (throw x))
    (if-some [next (done b)]
      (do (set! (.-head ps) next)
          ((.-notifier ps)))
      (if-some [prev (.-tail ps)]
        (do (set! (.-tail ps) nil)
            (set! (.-head ps)
              (loop [prev prev
                     next nil]
                (let [swap (.-queue prev)]
                  (set! (.-queue prev) next)
                  (if (nil? swap) prev (recur swap prev)))))
            ((.-notifier ps)))
        (set! (.-head ps) nil))) x))

(defn ready [^Choice c]
  (when (set! (.-busy c) (not (.-busy c)))
    (loop []
      (let [b (.-branch c)
            par (.-parallelism c)
            curr (.-current b)]
        (if (nil? par)
          (let [prev fiber
                x (try (set! fiber b)
                       ((.-coroutine c))
                       (catch :default e
                         (set! (.-done c) true) e))]
            (set! fiber prev)
            (if (identical? x b)
              (when (set! (.-busy c) (not (.-busy c))) (recur))
              (let [ps (root b)]
                (set! (.-current b) x)
                (if-some [n (.-notifier ps)]
                  (if (nil? (.-head ps))
                    (do (set! (.-head ps) b) (n))
                    (do (set! (.-queue b) (.-tail ps))
                        (set! (.-tail ps) b)))
                  (done b)))))
          (if (.-done c)
            (let [p (.-prev c)]
              (set! (.-prev c) nil)
              (if (identical? c p)
                (do (if (instance? Branch curr)
                      (move b curr)
                      (when (instance? Processor curr)
                        (loop [pr ^Processor curr]
                          (let [pr (.-next pr)]
                            (move b (.-child pr))
                            (when-not (identical? pr (.-current b))
                              (recur pr))))))
                    (discard b))
                (let [n (.-next c)]
                  (set! (.-prev n) p)
                  (set! (.-next p) n)
                  (when (identical? c (.-choice b))
                    (set! (.-choice b) p)
                    (if (nil? curr)
                      (ack p)
                      (when (instance? Processor curr)
                        (let [pivot (.-child pr)]
                          (set! (.-current b) pivot)
                          (set! (.-parent pivot) b)
                          (loop [pr ^Processor curr]
                            (let [pr (.-next pr)]
                              (when-not (identical? pr curr)
                                (move pivot (.-child pr)))))
                          (set! (.-prev pr) pr)
                          (set! (.-next pr) pr))))))))
            (if (pos? par)
              (do (set! (.-busy c) false) (branch c))
              (if (neg? par)
                (if (identical? c (.-choice b))
                  (if (nil? curr)
                    (do (set! (.-busy c) false) (choose c))
                    (do (set! (.-done c) true) (walk (.-next ^Branch curr))))
                  (do (set! (.-done c) true) (cancel (.-next c))))
                (set! (.-done c) true)))))))))

(defn suspend [^Branch b par flow]
  (let [c (.-choice b)]
    (set! (.-parallelism c) par)
    (set! (.-iterator c)
      (flow
        (fn
          ([]
           (ready c))
          ([x]
           (set! (.-current (.-branch c)) x)
           (ready c)))
        (fn
          ([]
           (set! (.-done c) true)
           (ready c))
          ([x]
           (set! (.-done c) true)
           (set! (.-current (.-branch c)) x)
           (ready c)))))
    (when-not (.-live c)
      ((.-iterator c))) b))

(defn resume [^Branch b]
  (let [c (.-choice b)
        x (.-current b)]
    (set! (.-current b) nil)
    (when (.-done c)
      (set! (.-done c) false)
      (throw x)) x))

(defn run [cr n t]
  (let [ps (->Process n t nil nil nil)
        b (->Branch ps nil nil nil nil nil)
        c (->Choice b nil nil nil nop nil true false false)]
    (->> b
      (set! (.-prev b))
      (set! (.-next b))
      (set! (.-child ps)))
    (->> c
      (set! (.-prev c))
      (set! (.-next c))
      (set! (.-choice b)))
    (boot cr c) ps))