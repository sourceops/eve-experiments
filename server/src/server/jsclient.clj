(ns server.jsclient
  (:require
   [clojure.stacktrace :refer [print-stack-trace]]
   [org.httpkit.server :as httpserver]
   [clojure.data.json :as json]
   [server.db :as db]
   [server.edb :as edb]
   [server.repl :as repl]
   [server.exec :as exec]
   [server.compiler :as compiler]
   [server.smil :as smil]
   [clojure.string :as string]
   [clojure.pprint :refer [pprint]]))

(def clients (atom {}))

(def DEBUG true)
(def bag (atom 10))

(defn quotify [x] (str "\"" (string/replace (string/replace x "\n" "\\n") "\"" "\\\"") "\""))
(defn format-json [x]
  (condp #(%1 %2) x
    string? (quotify x)
    keyword? (quotify x) ;;@NOTE: should this coerce to string?
    symbol? (quotify x)
    map? (str "{" (reduce-kv (fn [b k v] (str b (if (> (count b) 0) ", ") (format-json k) ":" (format-json v))) "" x) "}")
    coll? (str "[" (string/join "," (map format-json x)) "]")
    nil? "null"
    x))

(defn timestamp []
  (.format (java.text.SimpleDateFormat. "hh:mm:ss") (java.util.Date.)))

(defn send-result [channel id fields results]
  (let [client (get @clients channel)
        message {"type" "result"
                 "id" id
                 "fields" fields
                 "values" results}]
    (httpserver/send! channel (format-json message))
    (when DEBUG
      (println "<- result" id "to" (:id client) "@" (timestamp))
      (pprint message))))

(defn send-error [channel id error]
  (let [client (get @clients channel)
        data (ex-data error)
        data (if (:expr data)
               (assoc data :expr (with-out-str (smil/print-smil (:expr data))))
               data)
        message {"type" "error"
                 "id" id
                 "cause" (.getMessage error)
                 "stack" (with-out-str (print-stack-trace error))
                 "data" data}]
    (httpserver/send! channel (format-json message))
    (when DEBUG
      (println "<- error" id "to" (:id client) "@" (timestamp))
      (pprint message))))

(defn start-query [db query id channel]
  (let [fields (or (second query) [])
        results (atom ())
        [form fields]  (repl/form-from-smil query)
        prog (compiler/compile-dsl db @bag form)
        handler (fn [op tuple]
                  (condp = op
                    'insert (swap! results conj (vec tuple))
                    'flush (do (send-result channel id fields @results)
                               (reset! results '()))
                    'error (send-error channel id (ex-info "Failure to WEASL" {:data (str tuple)}))))
        e (exec/open db prog handler)]
    (e 'insert)
    (e 'flush)))

(defn handle-connection [db channel]
  ;; this seems a little bad..the stack on errors after this seems
  ;; to grow by one frame of org.httpkit.server.LinkingRunnable.run(RingHandler.java:122)
  ;; for every reception. i'm using this interface wrong or its pretty seriously
  ;; damaged
  (swap! clients assoc channel {:id (gensym "client") :queries []})
  (println "-> connect from" (:id (get @clients channel)) "@" (timestamp))
  (httpserver/on-receive
   channel
   (fn [data]
     ;; create relation and create specialization?
     (let [client (get @clients channel)
           input (json/read-str data)
           id (input "id")
           t (input "type")]
       (println "->" t id "from" (:id client) "@" (timestamp))
       (try
         (condp = t
           "query"
           (let [query (input "query")
                 expanded (when query (smil/unpack db (smil/read query)))]
             (println "  Raw:")
             (println "   " (string/join "\n    " (string/split query #"\n")))
             (println "  Expanded:")
             (smil/print-smil expanded :indent 4)
             (condp = (first expanded)
               'query (start-query db expanded id channel)
               'define! (do
                          (repl/define db expanded)
                          (send-result channel id [] []))
               (throw (ex-info (str "Invalid query wrapper " (first expanded)) {:expr expanded}))))
           (throw (ex-info (str "Invalid protocol message type " t) {:message input})))
         (catch clojure.lang.ExceptionInfo error
           (send-error channel id error))
         ))))

  (httpserver/on-close
   channel
   (fn [status]
     (println "-> close from" (:id (get @clients channel)) "@" (timestamp))
     ;; @TODO: cleanup any running computations?
     (swap! clients dissoc channel))))


;; @NOTE: This is trivially exploitable and needs to replaced with compojure or something at some point
(defn serve-static [channel uri]
  (let [prefix (str (.getCanonicalPath (java.io.File. ".")) "/../")]
    (httpserver/send! channel
                      {:status 200
                       :headers {"Expires" "0"
                                 "Cache-Control" "no-cache, private, pre-check=0, post-check=0, max-age=0"
                                 "Pragma" "no-cache"
                                 }
                       :body (slurp (str prefix uri))})))

(defn async-handler [db content]
  (fn [ring-request]
    (httpserver/with-channel ring-request channel    ; get the channel
      (if (httpserver/websocket? channel)
        (handle-connection db channel)
        (condp = (second (string/split (ring-request :uri) #"/"))
          ;;(= (ring-request :uri) "/favicon.ico") (httpserver/send! channel {:status 404})
          "bin" (serve-static channel (ring-request :uri))
          "css" (serve-static channel (ring-request :uri))
          "repl" (serve-static channel "repl.html")
          (httpserver/send! channel {:status 404}))))))


(import '[java.io PushbackReader])
(require '[clojure.java.io :as io])

(def server (atom nil))

(defn serve [db address]
  ;; its really more convenient to allow this to be reloaded
  ;;  (let [content
  ;;        (apply str (map (fn [p] (slurp (clojure.java.io/file (.getPath (clojure.java.io/resource p)))))
  ;;                        '("translate.js"
  ;;                          "db.js"
  ;;                          "edb.js"
  ;;                          "svg.js"
  ;;                          "websocket.js")))]
  ;; xxx - wire up address
  (when-not (nil? @server)
    (@server :timeout 0))
  (reset! server
          (try (httpserver/run-server (async-handler db "<http><body>foo</body><http>") {:port 8081})
               (catch Exception e (println (str "caught exception: " e (.getMessage e)))))))
