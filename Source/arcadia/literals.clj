(ns arcadia.literals
  (:require [arcadia.internal.namespace])
  (:import [System.Reflection
            FieldInfo
            ConstructorInfo
            BindingFlags
            ParameterInfo]
           [System TimeSpan]
           [UnityEngine Debug]
           [System.Reflection Assembly]
           [System.Diagnostics Stopwatch]))

(def ^Stopwatch sw3 (Stopwatch.))
(.Start sw3)

;; ============================================================
;; object database 
;; ============================================================

;; TODO where should this lift? 
;; TODO is the atom needed?
(def ^:dynamic *object-db* (atom {}))

(defn db-put [^UnityEngine.Object obj]
  (let [id (.GetInstanceID obj)]
    (swap! *object-db* assoc id obj)
    id))

;; TODO handle nil
;; clojure errors out if this returns nil
;; considers the dispatch to have failed... 
(defn db-get [id]
  (get @*object-db* id))


;; ============================================================
;; constructor wrangling
;; ============================================================

(defn sorted-fields
  "Sequence of all private and public instance FieldInfos of type
   sorted by name"
  [type]
  (->> (.GetFields type
                   (enum-or BindingFlags/Public BindingFlags/NonPublic BindingFlags/Instance))
       (sort-by #(.Name ^FieldInfo %))))

(defn field-values
  "Vector of values of all private and public instance fields of obj
   sorted by the names of the field"
  [obj]
  (let [type (.GetType obj)
        fields (sorted-fields type)]
    (mapv #(.GetValue ^FieldInfo % obj) fields)))

(defn instance-from-values
  "Create an instance of type from the values vector, assumed
  to be sorted by the name of the fields (as generated by field-values)"
  [type values]
  (let [obj (Activator/CreateInstance type)
        fields (sorted-fields type)]
    (->> fields
         (map (fn [val ^FieldInfo field]
                (.SetValue field obj
                           (Convert/ChangeType
                             val
                             (.FieldType field))))
              values)
         dorun)
    obj))

;; ============================================================
;; value types
;; ============================================================

(defn type-symbol [t]
  (cond (symbol? t) t
        (isa? (type t) Type) (let [^Type t t] (symbol (.FullName t)))
        :else (throw (Exception. (str t " is not a type or a symbol")))))

(defn- obsolete? [t]
  (some #(instance? ObsoleteAttribute %) (.GetCustomAttributes t false)))

(def value-types
  (->> (Assembly/Load "UnityEngine")
       .GetTypes
       (filter #(.IsValueType ^Type %))
       (filter #(.IsVisible ^Type %))
       (filter #(= "UnityEngine" (.Namespace ^Type %)))
       (remove obsolete?)
       (remove #(.IsEnum ^Type %))
       (remove #(.IsNested ^Type %))))

(defn parser-for-value-type [^Type t]
  `(defn ~(symbol (str "parse-" (.Name t))) [params#]
     (instance-from-values
       ~(-> t .FullName symbol)
       params#)))

(defn value-type-print-dup-impl [obj ^System.IO.TextWriter stream]
  (let [type (.GetType obj)
        type-name (symbol (.FullName type))]
    (.Write stream
            (str "#=(arcadia.literals/instance-from-values "
                 type-name
                 " "
                 (field-values obj)
                 ")"))))

(defn install-value-type-print-dup [^Type t]
  (.addMethod ^clojure.lang.MultiFn print-dup t value-type-print-dup-impl))

(defn value-type-print-method-impl [obj ^System.IO.TextWriter w]
  (let [type (.GetType obj)]
    (.Write w
            (str "#unity/" (.Name type) " "
                 (field-values obj)))))

(defn install-value-type-print-method [^Type t]
  (.addMethod ^clojure.lang.MultiFn print-method t value-type-print-method-impl))

(defn install-parser-for-value-type [^Type type]
  `(alter-var-root
     (var clojure.core/*data-readers*)
     assoc
     (quote ~(symbol (str "unity/" (.Name type))))
     (var ~(symbol (str "arcadia.literals/parse-" (.Name type))))))


(defmacro ^:private value-type-stuff []
  (cons `do
    (for [t value-types]
      (list `do
        (parser-for-value-type t)
        (install-parser-for-value-type t)))))

(def ^Stopwatch sw (Stopwatch.))
(.Start sw)

(value-type-stuff)

(doseq [t value-types]
  (install-value-type-print-method t)
  (install-value-type-print-dup t))

(.Stop sw)
(Debug/Log
  (str "Milliseconds to value type parser eval stuff: "
       (.TotalMilliseconds (.Elapsed sw))))

;; ============================================================
;; object types
;; ============================================================

(def object-types
  (->> (Assembly/Load "UnityEngine")
       .GetTypes
       (filter #(isa? % UnityEngine.Object))))

(defn parse-object [id]
  (or (db-get id)
      (do
        (UnityEngine.Debug/Log (str "Cant find object with ID " id))
        (UnityEngine.Object.))))

(defmethod print-dup
  UnityEngine.Object [^UnityEngine.Object v ^System.IO.TextWriter w]
  (.Write w (str "#=(arcadia.literals/db-get " (.GetInstanceID v) ")")))

(defmethod print-method
  UnityEngine.Object [^UnityEngine.Object v ^System.IO.TextWriter w]
  (.Write w (str "#unity/" (.. v GetType Name) " "(db-put v))))

(defn install-parser-for-object-type [^Type type]
  `(alter-var-root
     (var clojure.core/*data-readers*)
     assoc
     (quote ~(symbol (str "unity/" (.Name type))))
     (var ~(symbol (str "arcadia.literals/parse-object")))))

(defmacro ^:private object-type-stuff []
  (cons `do
    (for [t object-types]
      (list `do
        ;; object types share the same parser
        (install-parser-for-object-type t)))))



(def ^Stopwatch sw2 (Stopwatch.))
(.Start sw2)

(object-type-stuff)

(.Stop sw2)
(Debug/Log
  (str "Milliseconds to object type parser eval stuff: "
       (.TotalMilliseconds (.Elapsed sw2))))


;; AnimationCurves are different
;; finish
(comment 
  (defmethod print-dup
    UnityEngine.AnimationCurve [ac stream]
    (.Write stream
            (str "#=(UnityEngine.AnimationCurve. "
                 "(into-array ["
                 (apply str 
                        (->> ac
                             .keys
                             (map #(str "(UnityEngine.Keyframe. "
                                        (.time %)
                                        (.value %)
                                        (.inTangent %)
                                        (.outTangent %)
                                        ")"))
                             (interleave (repeat " "))))
                 ")")))
  (defmethod print-method
    UnityEngine.AnimationCurve [ac w]
    (.Write w
            (str "#unity/AnimationCurve"
                 (.GetInstanceID ~v))))
  
  (defn parse-AnimationCurve [v]
    (new UnityEngine.AnimationCurve (into-array (map eval (first v)))))
  
  (alter-var-root
    #'clojure.core/*data-readers*
    assoc
    'unity/AnimationCurve
    #'arcadia.literals/parse-AnimationCurve))


(.Stop sw3)
;; (Debug/Log
;;   (str "Milliseconds to namespace eval stuff: "
;;        (.TotalMilliseconds (.Elapsed sw3))))

;; (Debug/Log "At end of arcadia.literals. *data-readers*:")
;; (Debug/Log *data-readers*)

;; (Debug/Log "At end of arcadia.literals. (.getRawRoot #'*data-readers*):")
;; (Debug/Log (.getRawRoot #'*data-readers*))

;; ============================================================
;; for defmutable:

(defn- parse-user-type-dispatch [{:keys [:arcadia.core/mutable-type]}]
  mutable-type)

(defmulti parse-user-type
  "This multimethod should be considered an internal, unstable
  implementation detail for now. Please refrain from extending it."
  parse-user-type-dispatch)

;; ugh
;; so maybe the type and its parser haven't been loaded yet because
;; their namespace hasn't, yknow
(def seen-user-type-names (atom #{}))

(defmethod parse-user-type :default [{t :arcadia.core/mutable-type
                                      :as spec}]
  (if (contains? @seen-user-type-names t)
    (throw (Exception. (str "Already seen type " t ", something's wrong.")))
    (do (swap! seen-user-type-names conj t)
        (let [ns-name (-> (clojure.string/join "."
                            (butlast
                              (clojure.string/split (name t) #"\." )))
                          (clojure.string/replace "_" "-")
                          symbol)]
          (arcadia.internal.namespace/quickquire ns-name)
          (parse-user-type spec)))))

(alter-var-root #'*data-readers* assoc 'arcadia.core/mutable #'parse-user-type)

;; and we also have to do this, for the repl:
(when (.getThreadBinding ^clojure.lang.Var #'*data-readers*)
  (set! *data-readers*
    (merge *data-readers*
      ;; I guess. so weird
      (.getRawRoot #'*data-readers*)
      ;;'arcadia.core/mutable #'parse-user-type
      )))

;; ============================================================

;; this is stupid

(def the-bucket (.getRawRoot #'*data-readers*))

;; (Debug/Log "At end of arcadia.literals. the-bucket:")
;; (Debug/Log the-bucket)


