(ns avout.atom
  (:require [zookeeper :as zk]
            [zookeeper.data :as data]
            [avout.locks :as locks])
  (:import (org.apache.zookeeper KeeperException$BadVersionException)
           (clojure.lang IRef)))

;; PROTOCOLS

(defprotocol AtomData
  (nodeName [this] "Returns the ZooKeeper node name associated with this AtomData.")
  (getValue [this] "Returns a map containing the :value and a :version
    which may just be the current value or a version number and is used in
    compareAndSet to determine if an update should occur.")
  (setValue [this value]))

(defprotocol AtomReference
  (swap [this f] [this f args])
  (reset [this new-value])
  (compareAndSet [this old-value new-value]))

(deftype ZKAtomReference [client atomData validator watch lock]

  AtomReference
  (swap [this f] (.swap this f nil))

  (compareAndSet [this old-value new-value]
    (if (and @validator (not (@validator new-value)))
      (throw (IllegalStateException. "Invalid reference state"))
      (locks/with-lock (.writeLock lock)
        (let [value (.getValue atomData)]
          (and (= old-value value)
               (.setValue atomData new-value))))))

  (swap [this f args]
    (locks/with-lock (.writeLock lock)
      (let [new-value (apply f (.getValue atomData) args)]
        (if (and @validator (not (@validator new-value)))
          (throw (IllegalStateException. "Invalid reference state"))
          (do (.setValue atomData new-value)
              new-value)))))

  (reset [this new-value]
    (locks/with-lock (.writeLock lock)
      (if (and @validator (not (@validator new-value)))
        (throw (IllegalStateException. "Invalid reference state"))
        (do (.setValue atomData new-value)
            new-value))))

  IRef
  (deref [this]
    (locks/with-lock (.readLock lock)
      (.getValue atomData)))

  (addWatch [this key callback] ;; callback params: akey, aref, old-val, new-val, but old-val will be nil
    (let [watcher (fn watcher-fn [event]
                    (when (= :NodeDataChanged (:event-type event))
                      (let [new-value (.deref this)]
                        (callback key this nil new-value)))
                    (zk/exists client (.nodeName atomData) :watcher watcher-fn))]
      (zk/exists client (.nodeName atomData) :watcher watcher)
      this))

  (getWatches [this] (throw (UnsupportedOperationException.)))

  (removeWatch [this key] (throw (UnsupportedOperationException.)))

  ;;throw new IllegalStateException("Invalid reference state");
  (setValidator [this f] (reset! validator f))

  (getValidator [this] @validator))

;; Versions of Clojure's Atom functions for use with AtomReferences

(defn swap!!
  "Cannot use standard swap! because Clojure expects a clojure.lang.Atom."
  ([atom f & args] (.swap atom f args)))

(defn reset!!
  "Cannot use standard reset! because Clojure expects a clojure.lang.Atom."
  ([atom new-value] (.reset atom new-value)))

(defn compare-and-set!!
  "Cannot use standard reset! because Clojure expects a clojure.lang.Atom."
  ([atom old-value new-value] (.compareAndSet atom old-value new-value)))

;; Default implementation of AtomReference that uses ZooKeeper has the data value container.

(defn serialize-form
  "Serializes a Clojure form to a byte-array."
  ([form]
     (data/to-bytes (pr-str form))))

(defn deserialize-form
  "Deserializes a byte-array to a Clojure form."
  ([form]
     (read-string (data/to-string form))))

(deftype ZKAtomData [client name]
  AtomData
  (nodeName [this] name)

  (getValue [this]
    (let [{:keys [data stat]} (zk/data client name)]
      (deserialize-form data)))

  (setValue [this new-value] (zk/set-data client name (serialize-form new-value) -1)))

(defn zk-atom
  ([client name]
     (zk-atom client name nil))
  ([client name init-value]
     (zk/create client name :persistent? true)
     (doto (ZKAtomReference. client (ZKAtomData. client name)
                             (atom nil) (atom {})
                             (locks/distributed-read-write-lock client :lock-node (str name "/lock")))
       (.reset init-value))))
