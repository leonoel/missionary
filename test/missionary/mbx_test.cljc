(ns missionary.mbx-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(lc/defword post [id v & events] [v (l/over) (l/change id) (apply lc/call 1 events) (l/lose)])
(lc/defword fetch [id task-id & events] [(l/dup) (l/change id) (apply l/start task-id events)])

(t/deftest success
  (t/testing "post fetch"
    (t/is (= []
            (lc/run
              (l/store
                (m/mbx) (l/insert :mbx)
                (post :mbx 1)
                (fetch :mbx :fetch (l/succeeded :fetch #{1})))))))
  (t/testing "fetch post"
    (t/is (= []
            (lc/run
              (l/store
                (m/mbx) (l/insert :mbx)
                (fetch :mbx :fetch)
                (post :mbx 1 (l/succeeded :fetch #{1})))))))
  (t/testing "post post fetch fetch"
    (t/is (= []
            (lc/run
              (l/store
                (m/mbx) (l/insert :mbx)
                (post :mbx 1)
                (post :mbx 2)
                (fetch :mbx :fetch (l/succeeded :fetch #{1}))
                (fetch :mbx :fetch (l/succeeded :fetch #{2})))))))
  ;; this case is non-deterministic for now
  #_(t/testing "fetch fetch post post"
    (t/is (= []
            (lc/run
              (l/store
                (m/mbx) (l/insert :mbx)
                (fetch :mbx :fetch1)
                (fetch :mbx :fetch2)
                (post :mbx 1 (l/succeeded :fetch1 #{1}))
                (post :mbx 2 (l/succeeded :fetch2 #{2}))))))))

(t/deftest cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/mbx) (l/insert :mbx)
              (fetch :mbx :fetch)
              (l/cancel :fetch (l/failed :fetch (partial instance? Cancelled))))))))
