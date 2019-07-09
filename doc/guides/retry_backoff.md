# Retry with backoff

In distributed systems, failures happen : servers crash and reboot, network is unreliable (especially when targeting mobile devices). In many cases it's perfectly acceptable to retry a request after failure, hoping for better conditions. Special care should be taken, however :
* Failures worth retrying are those due to a temporary degradation of technical environment, not those due to business errors (in this case, there's no point retrying).
* Retry rate must be under control, because spamming a server experiencing a burst of load is likely to make the situation even worse.

In this guide, we'll implement a simple generic backoff retry strategy.

```clojure
(require '[missionary.core :as m])
```

In order to figure out if a failed request is worth retrying, we'll tag the exception info with `:worth-retrying`. The backoff strategy will be a sequence of numbers representing the delays (in milliseconds) of each retry we want to perform before actually propagating the error.

The test input will look like this :
```clojure
(def delays                          ;; Our backoff strategy :
  (->> 1000                          ;; first retry is delayed by 1 second
       (iterate (partial * 2))       ;; exponentially grow delay
       (take 5)))                    ;; give up after 5 retries

(def request                         ;; A mock request to exercise the strategy.
  (m/sp                              ;; Failure odds are made pretty high to
    (prn :attempt)                   ;; simulate a terrible connectivity
    (if (zero? (rand-int 6))
      :success
      (throw (ex-info "failed." {:worth-retrying true})))))
```

Now, we need a function to bundle this in a task applying given backoff strategy to given task. We define it recursively, the base case being reached when the sequence of retry delays is empty, at which point the request is returned unchanged.
```clojure
(defn backoff [request delays]
  (if-some [[delay & delays] (seq delays)]
    (m/sp
      (try (m/? request)
           (catch Exception e
             (if (-> e ex-data :worth-retrying)
               (do (m/? (m/sleep delay))
                   (m/? (backoff request delays)))
               (throw e)))))
    request))
```

Now testing.
```clojure
(m/? (backoff request delays))
:attempt
:attempt
:attempt
#=> :success
```
