(ns server.main
  (:gen-class)
  (:require
   [server.db :as db]
   [server.edb :as edb]
   [server.log :as log]
   [server.smil :as smil]
   [server.repl :as repl]
   [server.jsclient :as jsclient]))

(def db (atom nil))
(def trace (atom false))
(def service (atom true))

(defn -main [& args]
  ;; load existing database..change the way the user is bound here, should go through
  ;; a shim. should also not be exposed to weasl
  (when (nil? @db) (reset! db (edb/create-edb @repl/user)))
  (let [interactive (atom true)
        port (atom 8081)

        ;; load the local metadata before starting membership
        flag-map
        {
         "-d" (fn [] (reset! interactive false))
         "-n" (fn [] (reset! service false))
         "-t" (fn [] (reset! trace true))
         }

        parameter-map
        {"-s" log/set-pathname
         "-p" (fn [x] (reset! port (Integer. x)))
         
         "-f" (fn [x]
                (reset! interactive false)
                (reset! service false)
                (repl/read-all @db (list 'load x) @trace))
                
         "-e" (fn [x] (try (repl/eeval @db (smil/read x) @trace)
                           (catch Exception e
                             (println "error" e))))
         }


        arglist (fn arglist [args]
                  (if (empty? args) ()
                      (if-let [f (flag-map (first args))]
                        (do (f)
                            (arglist (rest args)))
                        (if-let [p (parameter-map (first args))]
                          ;; check to make sure we have such a thing?
                          (do (p (second args))
                              (arglist (rest (rest args))))
                          (println "invalid argument" (first args))))))]
    (arglist args)
    (when @service (jsclient/serve @db @port))
    (when @interactive (repl/rloop @db))))
