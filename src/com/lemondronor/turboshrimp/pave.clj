(ns com.lemondronor.turboshrimp.pave
  "Code for reading and parsing video data in Parrot Video
  Encapsulated (PaVE) format.

  To actually decode PaVE video, you will need something like
  the [turboshrimp-xuggler](https://github.com/wiseman/turboshrimp-xuggler)
  library."
  (:require [clojure.tools.logging :as log])
  (:import (java.io EOFException InputStream)
           (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks Condition Lock ReentrantLock)))


(defn- bytes-to-int [^bytes ba offset num-bytes]
  (let [c 0x000000FF]
    (reduce
     #(+ %1 (bit-shift-left (bit-and (aget ba (+ offset %2)) c) (* 8 %2)))
     0
     (range num-bytes))))


(defn- get-short [ba offset]
  (bytes-to-int ba offset 2))


(defn- get-int [ba offset]
  (bytes-to-int ba offset 4))


(defn- get-uint8 [ba offset]
  (bytes-to-int ba offset 1))


(def ^:no-doc header-size 76)


(defn- full-read
  "Reads a specified number of bytes from an InputStream.

  Compensates for short reads by reading in a loop until the desired
  number of bytes has been read.  Returns a byte array.

  If the first attempt to read from the stream returns 0 bytes, this
  function will return nil.  If subsequent attempts to read from the
  stream return 0 bytes, an EOFException is thrown."
  ^bytes [^InputStream is num-bytes]
  (let [^bytes ba (byte-array num-bytes)]
    (loop [offset 0]
      (let [num-bytes-read (.read is ba offset (- num-bytes offset))]
        (if (<= num-bytes-read 0)
          (if (= offset 0)
            nil
            (throw (EOFException. (str "EOF reading PaVE stream " is))))
          (if (< (+ num-bytes-read offset) num-bytes)
            (recur (+ offset num-bytes-read))
            ba))))))


(def ^{"[B" true, :no-doc true} pave-signature (.getBytes "PaVE"))


(defn- pave-frame?
  "Checks whether a frame has the 'PaVE' signature."
  [^bytes ba]
  (and (= (aget ba 0) (aget pave-signature 0))
       (= (aget ba 1) (aget pave-signature 1))
       (= (aget ba 2) (aget pave-signature 2))
       (= (aget ba 3) (aget pave-signature 3))))


(defn ^:no-doc read-frame
  "Reads a PaVE frame from an InputStream.

  Skips over non-PaVE frames and returns the next PaVE frame.  Returns
  nil if there are no more frames."
  [^InputStream is]
  (if-let [ba (full-read is header-size)]
    (let [this-header-size (get-short ba 6)
          payload-size (get-int ba 8)]
      (assert (>= this-header-size header-size))
      (when (> this-header-size header-size)
        ;; Header size can change from version to version, so we read
        ;; and ignore any remaining bytes.
        (full-read is (- this-header-size header-size)))
      (if (pave-frame? ba)
        ;; We don't parse out the full header, just some of the parts
        ;; we're interested in.
        {:header-size header-size
         :payload (or (full-read is payload-size)
                      (throw
                       (EOFException.
                        (str "EOF while reading payload from " is))))
         :display-dimensions [(get-short ba 16) (get-short ba 18)]
         :encoded-dimensions [(get-short ba 12) (get-short ba 14)]
         :frame-number (get-int ba 20)
         :timestamp (get-int ba 24)
         :frame-type (get-uint8 ba 30)
         :slice-index (get-uint8 ba 43)}
        (do
          (log/debug "Skipping non-PaVE frame")
          (recur is))))
    nil))


(def ^:no-doc IDR-FRAME 1)
(def ^:no-doc I-FRAME 2)
(def ^:no-doc P-FRAME 3)


(defn ^:no-doc i-frame?
  "Checks whether a frame is an I-frame (keyframe)."
  [frame]
  (let [frame-type (:frame-type frame)]
    (or (= frame-type I-FRAME)
        ;; IDR-frames are also I-frames.
        (and (= frame-type IDR-FRAME)
             (= (:slice-index frame) 0)))))


(defrecord FrameQueue [queue num-frames-dropped lock not-empty reduce-latency?])

(alter-meta! (var ->FrameQueue) #(assoc % :private true))
(alter-meta! (var map->FrameQueue) #(assoc % :private true))


(defn make-frame-queue
  "A frame queue that implements latency reduction by default.

  This queue implements the recommended latency reduction technique
  for AR.Drone video, which is to drop any unprocessed P-frames
  whenever an I-frame is received.

  To turn off latency reduction, you can call this with
  `:reduce-latency? false`."
  [& {:keys [reduce-latency?]
      :or {reduce-latency? true}}]
  (let [^Lock lock (ReentrantLock.)]
    (map->FrameQueue
     {:queue (atom '())
      :num-dropped-frames (atom 0)
      :lock lock
      :not-empty (.newCondition lock)
      :reduce-latency? (atom reduce-latency?)})))


(defn dropped-frame-count
  "Returns the number of video frames that have been dropped in order
  to reduce latency."
  [queue]
  @(:num-dropped-frames queue))


(defn pull-frame
  "Pulls a video frame from a frame queue.

  Blocks until a frame is available.  An optional timeout (in
  milliseconds) can be specified, in which case the call will return
  nil if a frame isn't available in time."
  [queue & [timeout-ms]]
  (let [q (:queue queue)
        ^Lock lock (:lock queue)
        ^Condition not-empty (:not-empty queue)]
    (.lock lock)
    (try
      (loop [num-tries 0]
        (if-let [frames (seq @q)]
          (let [[f & new-q] frames]
            (reset! q new-q)
            f)
          (if (and timeout-ms (> num-tries 0))
            nil
            (do
              (if timeout-ms
                (.await not-empty timeout-ms TimeUnit/MILLISECONDS)
                (.await not-empty))
              (recur (inc num-tries))))))
      (finally
        (.unlock lock)))))


(defn queue-frame
  "Pushes a video frame into a frame queue."
  [queue frame]
  (let [q (:queue queue)
        ^Lock lock (:lock queue)
        ^Condition not-empty (:not-empty queue)]
    (.lock lock)
    (try
      (do
        (if (and @(:reduce-latency? queue)
                 (i-frame? frame))
          (let [num-skipped (count @q)]
            (when (> num-skipped 0)
              (log/debug "Skipped" num-skipped "frames")
              (swap! (:num-dropped-frames queue) + num-skipped))
            (reset! q (list frame)))
          (swap! q concat (list frame)))
        (.signal not-empty))
      (finally
        (.unlock lock)))))
