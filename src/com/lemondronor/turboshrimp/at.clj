(ns com.lemondronor.turboshrimp.at
  (:require [clojure.string :as string]
            [com.lemondronor.turboshrimp.commands :as commands])
  (:import (java.nio ByteBuffer)))

(defn build-command-int [command-bit-vec]
  (reduce bit-set 0 command-bit-vec))

(defn cast-float-to-int [f]
  (let [bbuffer (ByteBuffer/allocate 4)]
    (.put (.asFloatBuffer bbuffer) 0 f)
    (.get (.asIntBuffer bbuffer) 0)))

(defn build-base-command [command-class counter]
  (str command-class "=" counter ","))

(defn build-ref-command [command-key counter]
  (let [{:keys [command-class command-bit-vec]} (command-key commands/commands)]
    (str (build-base-command command-class counter)
         (build-command-int command-bit-vec)
         "\r")))

(defn build-pcmd-command [command-key counter & [[v w x y]]]
  (let [{:keys [command-class command-vec dir] or {dir 1}} (command-key commands/commands)
        v-val (when v (cast-float-to-int (* dir v)))
        w-val (when w (cast-float-to-int w))
        x-val (when x (cast-float-to-int x))
        y-val (when y (cast-float-to-int y))]
    (str (build-base-command command-class counter)
         (string/join "," (replace {:v v-val :w w-val :x x-val :y y-val} command-vec))
         "\r")))

(defn build-simple-command [command-key counter]
  (let [{:keys [command-class value]} (command-key commands/commands)]
    (str (build-base-command command-class counter)
         value
         "\r")))

(defn build-config-command [command-key counter]
  (let [{:keys [command-class option value]} (command-key commands/commands)]
    (str (build-base-command command-class counter)
         (string/join "," [option, value])
         "\r")))

(defn build-command [counter command-key & values]
  (let [{:keys [command-class]} (command-key commands/commands)]
    (case command-class
      "AT*REF"  (build-ref-command command-key counter)
      "AT*PCMD" (build-pcmd-command command-key counter values)
      "AT*FTRIM" (build-simple-command command-key counter)
      "AT*COMWDG" (build-simple-command command-key counter)
      "AT*CONFIG" (build-config-command command-key counter)
      "AT*CTRL" (build-simple-command command-key counter)
      (throw
       (Exception. (str "Unknown command: " command-key))))))


(defn commands-bytes [counter commands]
  (->> (map (fn [seqnum command]
              (apply build-command seqnum command))
            (iterate inc counter)
            commands)
       (string/join)
       (.getBytes)))
