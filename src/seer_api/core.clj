(ns seer-api.core
  (:require [org.httpkit.server :as http])
  (:require [seer-api.elaborations :as ela])
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [seer-api.utils :as ut]
            [seer-api.db :as db])
  (:require [clojure.tools.logging :as log]))

(defonce conn-and-conf (atom nil))

;;
;; REST interface
;;
;; POST v1/forecasts  --> :job-id
;;   - job creation
;;   - data validation (TODO: verify via SeerDataCruncher)
;;   - ETA calculation
;;   - processing time series
;;   - store status for every step
;;   - store data forecasted
;; GET  /forecasts/:job-id --> current status with ETA
;; GET  /forecasts/:job-id/result --> return the forecasted values
;; GET  /forecasts/:job-id/elaboration-details --> return also the full timeseries analyzed
;;


(defroutes
  site
  (context "/v1" []

           (POST "/forecasts" {body :body :as r}
                 (let [job-id (.toString (java.util.UUID/randomUUID))
                       {seer-path :path seer-core :core} (get-in @conn-and-conf [:config :seer])
                       collection (get-in @conn-and-conf [:config :db :collection])
                       input-file (or (get-in r [:params "file" :tempfile]) body)]

                   (db/store-job-status job-id (:db @conn-and-conf) collection)
                   (ut/copy-input-to-location job-id input-file seer-path)
                   (ela/start-background-processing job-id (:db @conn-and-conf) collection seer-path seer-core)
                   {:status 202
                    :body   {:job-id job-id}}
                   ))

           (GET "/forecasts/:job-id" [job-id]
                (let [data (db/find-status-by-job-id job-id (:db @conn-and-conf)
                                                     (get-in @conn-and-conf [:config :db :collection]))]
                  (if data
                    {:status  200
                     :headers {"Content-type" "application/json"}
                     :body    data}
                    {:status 404
                     :body   "the job-id does not exist!\n"})))

           (GET "/forecasts/:job-id/result" [job-id]
                (let [data (db/find-forecast-by-job-id job-id (:db @conn-and-conf)
                                                       (get-in @conn-and-conf [:config :db :collection]))]
                  (if data
                    {:status  200
                     :headers {"Content-type" "application/json"}
                     :body    data}
                    {:status 404
                     :body   "the job-id does not exist!\n"})))

           (GET "/forecasts/:job-id/elaboration-details" [job-id]
                (let [data (db/find-elaboration-by-job-id job-id (:db @conn-and-conf)
                                                          (get-in @conn-and-conf [:config :db :collection]))]
                  (if data
                    {:status  200
                     :headers {"Content-type" "application/json"}
                     :body    data}
                    {:status 404
                     :body   "the job-id does not exist!\n"})))
           )
  (route/not-found "Page not found\n"))

(comment
  (def conn-and-conf-aus (atom {:config {:db {:host "localhost", :db-name "seer-api", :collection "elaborations"}}
                                :seer   {:path "foo/path" :core "foo/core"}}))
  (-> @conn-and-conf-aus
      :config
      :seer
      :path)

  (get-in @conn-and-conf-aus [:config :seer])
  (get-in @conn-and-conf-aus [:config :db :collection])
  )


(defn catch-all [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception x
        (let [error-id (str "ERR-" (.toString (java.util.UUID/randomUUID)))]
          (log/error x (str "Exception number: " error-id))
          {:status  503
           :headers {"Content-Type" "application/json"}
           :body    {:status   "ERROR"
                     :message  "The operation couldn't be completed due to an internal error."
                     :error-id error-id}})))))


(defn print-request [handler]
  (fn [req]
    (clojure.pprint/pprint req)
    (handler req)))


(def app
  (-> site
      ;;print-request
      catch-all
      wrap-json-response
      (wrap-json-body {:keywords? true})
      wrap-params
      wrap-multipart-params
      ))


(defn start-server [{:keys [port]}]
  (println "Starting server on port:" port)
  (http/run-server #'app {:port port}))
