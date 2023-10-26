(ns probe.core
  "Treat the program's dynamic execution traces as first class system state;
   with some common conventions to route data to taps. Compatible with low
   level tap-enabled tools but provides convenience wrappers on top to
   simplify some common use cases."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [probe.wrap :as wrap]))

;; Constants

(defonce reserved-keys [:probe/ts :probe/thread-id :probe/tags :probe/bindings :probe/ns :probe/line])


;; ==============================================
;; Helpful transducers for probe state
;; ==============================================

(defn xform->transform
  "Returns a function that takes a tranducer and returns
   a transformer function that can be called manually. If
   the transducer is a filter, the default value will be
   returned"
  ([xform default]
   (partial (xform (fn [default state] state)) default))
  ([xform]
   (xform->transform xform nil)))
  

(defn with-tag
  "Transducer for states w/ provided tag"
  [tag]
  (filter #(and (:tags %) ((:tags %) tag))))

(defn with-all-tags
  "Function that returns payload if state has provided tags"
  [& tags]
  (let [tags (set tags)]
    (filter #(and (:tags %) (every? tags (:tags %))))))

(defn with-some-tags
  "Function that returns payload if state has provided tags"
  [& tags]
  (let [tags (set tags)]
    (filter #(and (:tags %) (some? tags (:tags %))))))

(defn- ns-keyword? [key]
  (and (keyword? key)
       (= "ns" (namespace key))))

(defn with-namespaces [ns & nss]
  {:pre [(ns-keyword? ns)
         (or (empty? nss)
             (every? ns-keyword? nss))]}
  (if (empty? nss)
    (with-tag ns)
    (apply with-some-tags (cons ns nss))))

(defn- filter-by-attribute
  ([pred attr-or-path]
   {:pre [(fn? pred)]}
   (let [path (if (sequential? attr-or-path) attr-or-path (vector attr-or-path))]
     (filter (fn [state]
               (when-let [val (get-in state path)]
                 (pred val))))))
  ([pred attr-or-path reference-value]
   {:pre [(fn? pred)]}
   (filter-by-attribute #(pred % reference-value) attr-or-path)))

(defn filter-value=
  [attr-or-path value]
  (filter-by-attribute = attr-or-path value))

(defn filter-value>
  [attr-or-path value]
  (filter-by-attribute > attr-or-path value))

(defn filter-value<
  [attr-or-path value]
  (filter-by-attribute < attr-or-path value))

(defn filter-matching
  "Returns state where for every k in state, the value in state equals
   the value in the provided map. If v is a function, the predict is
   applied to the value instead."
  [m]
  (filter #(every? (fn [[k v]]
                     (if (fn? v)
                       (v (get % k))
                       (= (get % k) v)))
                   m)))

(defn selecting
  "Function that filters state to only the provided keys"
  [& keys]
  (map #(select-keys % keys)))

(defn removing
  "Function that removes the spcified keys"
  [& keys]
  (map #(apply dissoc % keys)))

(defn removing-reserved
  "Remove all the default keys"
  []
  (apply removing reserved-keys))

(defn removing-bindings
  "Remove bindings"
  []
  (removing :probe/bindings))


;; =============================================
;;  PROBE POINTS 
;; =============================================


(defn expand-namespace
  "Generate all sub-namespaces for tag filtering:
   probe.foo.bar => [:ns/probe :ns/probe.foo :ns/probe.foo.bar]"
  [ns]
  {:pre [(string? (name ns))]}
  (->> (str/split (name ns) #"\.")
       (reduce (fn [paths name]
                 (if (empty? paths)
                   (list name)
                   (cons (str (first paths) "." name) paths)))
               nil)
       (map (fn [path] (keyword "ns" path)))))


(defonce ^:dynamic *capture-bindings* nil)

(defn- dynamic-var? [sym]
  (:dynamic (meta (resolve sym))))

(defn- valid-bindings? [list]
  (and (sequential? list)
       (every? symbol? list)
       (every? namespace list)
       (every? dynamic-var? list)))

(defn capture-bindings!
  "Establish a global capture list for bindings to be passed on the
   :probe.core/bindings key in the state object.  You'll need to filter
   these in a transform if you don't want them in your sink!  Also, this
   function expects fully qualified symbol names of the vars you wish to
   capture bindings for."
  [list]
  {:pre [(or (nil? list) (valid-bindings? list))]}
  (alter-var-root #'*capture-bindings* (fn [old] list)))

(defn grab-bindings []
  (->> *capture-bindings*
       (map (fn [sym]
              (when-let [var (resolve sym)]
                (when-let [val (var-get var)]
                  [sym val]))))
       (into {})))

(defmacro with-captured-bindings [bindings & body]
  `(binding [*capture-bindings* ~bindings]
     ~@body))

(defmacro without-captured-bindings [& body]
  `(binding [capture-bindings nil]
     ~@body))

(defn probe*
  "Probe the provided state in the current namespace using tags for dispatch"
  ([ns form tags state]
   (let [ntags (expand-namespace ns)
         bindings (grab-bindings)
         state (assoc state
                      :probe/tags (set (concat tags ntags))
                      :probe/ns (keyword "ns" (name ns))
                      :probe/thread-id  (.getId (Thread/currentThread))
                      :probe/ts (java.util.Date.))
         state (if (and bindings (not (empty? bindings)))
                 (assoc state :probe/bindings bindings)
                 state)
         state (if (and form (:line (meta form)))
                 (assoc state :probe/line (:line (meta form)))
                 state)]
     (tap> state)))
  ([tags form state]
   (probe* (ns-name *ns*) form tags state))
  ([tags state]
   (probe* (ns-name *ns*) nil tags state)))

(defmacro probe
  "Take a single map as first keyvals element, or an upacked
   list of key and value pairs. Add lexical context."
  ([state]
   `(let [state# ~state]
      (probe* (quote ~(ns-name *ns*)) (quote ~&form) (:tags state#) (dissoc state# :tags))))
  ([tags & keyvals]
   (let [state (if (and (= (count keyvals) 1)
                        (map? (first keyvals)))
                 (first keyvals)
                 (apply array-map keyvals))]
     `(probe* (quote ~(ns-name *ns*)) (quote ~&form) ~tags ~state))))


(defmacro probe-expr
  "Like logging/spy; generates a probe state with :form and return
   :value keys and the :probe/expr tag"
  [& body]
  (let [[tags thebody] (if (and (set? (first body)) (> (count body) 1))
                         [(cons :probe/expr (first body)) (rest body)]
                         [#{:probe/expr} body])]
    `(let [value# (do ~@thebody)]
       (probe ~tags
              :form '(do ~@(rest &form))
              :value value#)
       value#)))


;; -----------------------------------------
;; Probe vars
;; -----------------------------------------

(defn- state-watcher [tags transform-fn]
  (let [thetags (set (cons :probe/watch tags))]
    (fn [_ _ _ new]
      (let [state (if (map? new) new (assoc {} :value new))]
        (probe* thetags (if transform-fn (transform-fn state) state))))))

(defn- state? [ref]
  (let [type (type ref)]
    (or (= clojure.lang.Var type)
        #?(:clj (= clojure.lang.Ref type))
        (= clojure.lang.Atom type)
        #?(:clj (= clojure.lang.Agent type)))))

(defn- resolve-ref
  "Usually we want to the value of a Var and not the var itself when
   submitting a watcher.  Thus, we dereference the val with val-get
   and if the result is a state? element, we use that instead of the
   provided or referenced var"
  [ref]
  (cond (and (symbol? ref) (state? (var-get (resolve ref))))
        (var-get (resolve ref))
        (or (and (symbol? ref) (fn? (state? (resolve ref))))
            (and (var? ref) (fn? (var-get ref))))
        (throw (ex-info "Probing Vars that hold functions is verboten"))
        (and (symbol? ref) (state? (resolve ref)))
        (resolve ref)
        (and (var? ref) (state? (var-get ref)))
        (var-get ref)
        (state? ref)
        ref
        :default
        (throw (ex-info "Do not know how to probe provided reference"
                        {:ref ref :type (type ref)}))))


(defn probe-state!
  "Add a probe function to a state element or symbol
   that resolves to a state location."
  ([tags transform-fn ref]
   {:pre [(or (nil? transform-fn) (fn? transform-fn))]}
   (let [stateval (resolve-ref ref)]
     (add-watch stateval ::probe (state-watcher tags transform-fn))))
  ([tags ref]
   (probe-state! #{} nil ref)))

(defn unprobe-state!
  "Remove the probe function from the provided reference"
  [ref]
  (let [stateval (resolve-ref ref)]
    (remove-watch stateval ::probe)))


;; -----------------------------------------
;; Probe functions
;; -----------------------------------------

(defn- probe-fn-wrapper
  "Wrap f of var v to insert pre,post, and exception wrapping probes that
   match tags :entry-fn, :exit-fn, and :except-fn."
  [tags v f]
  (let [m (meta v)
        static (array-map :line (:line m) :fname (:name m))
        except-fn (set (concat [:probe/fn :probe/fn-except] tags))
        enter-tags (set (concat [:probe/fn :probe/fn-enter] tags))
        exit-tags (set (concat [:probe/fn :probe/fn-exit] tags))]
    (fn [& args]
      (do (probe* enter-tags (assoc static
                               :args args))
          (let [result (try (apply f args)
                            (catch java.lang.Throwable e
                              (probe* except-fn (assoc static
                                                  :exception e
                                                  :args args))
                              (throw e)))]
            (probe* exit-tags (assoc static
                                :args args
                                :value result))
            result)))))

;; Function probe API

(defn probe-fn!
  ([tags fsym]
     {:pre [(symbol? fsym)]}
     (wrap/wrap-var-fn fsym (partial probe-fn-wrapper tags)))
  ([fsym]
     (probe-fn! [] fsym)))

(defn unprobe-fn!
  ([tags fsym]
     {:pre [(symbol? fsym)]}
     (wrap/unwrap-var-fn fsym))
  ([fsym]
     (unprobe-fn! [] fsym)))

;; Probe all functions in a namespace

(defn- probe-var-fns
  "Probe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get wrap/as-var))
        (map probe-fn!))))

(defn- unprobe-var-fns
  "Unprobe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get wrap/as-var))
        (map probe-fn!))))

(defn probe-ns! [ns]
  (probe-var-fns (keys (ns-publics ns))))

(defn unprobe-ns! [ns]
  (unprobe-var-fns (keys (ns-publics ns))))

(defn probe-ns-all! [ns]
  (probe-var-fns (keys (ns-interns ns))))

(defn unprobe-ns-all! [ns]
  (unprobe-var-fns (keys (ns-interns ns))))


;; -------------------------------------
;; Easier management of taps
;; -------------------------------------

(defonce named-taps (atom {}))

(defn remove-named-tap
  "Remove the tap-fn recorded under name"
  [name]
  (when-let [f (get @named-taps name)]
    (remove-tap f)
    (swap! named-taps dissoc name)))

(defn add-named-tap
  "Adds a tap but retains the function identity and associates it with
   a name for easy removal later. Adds convenience signatures for
   applying a transducer over the values to sink-fn."
  ([name tap-fn]
   (when (get @named-taps name)
     (remove-named-tap name))
   (swap! named-taps assoc name tap-fn)
   (add-tap tap-fn))
  ([name xform tap-fn]
   (let [transform (xform->transform xform) ;; non accumulating reducer
         xtap-fn (fn [state] (as-> (transform state) v (when v (tap-fn v))))]
     (add-named-tap name xtap-fn))))

   
  
;; Tags

(def log-tag-seq
  [:error :warn :info :debug :trace])

(defn expand-tags [tags]
  (let [logs (set/intersection (set log-tag-seq) (set tags))]
    (loop [logtags log-tag-seq]
      (if (logs (first logtags))
        (concat (rest logtags) tags)
        (recur (rest logtags))))))

;; Log entry point

(defn- log-state
  ;; Pull out :exception, otherwise preserve order
  [ns form level keyvals]
;  {:pre [(keyword? level)]}
  (let [amap (apply array-map keyvals)
        exception (:exception amap)
        tags (set (concat (expand-tags [level]) (:tags amap)))
        state (if exception
                (assoc amap :exception (with-meta exception
                                         {:tag 'java.lang.Throwable}))
                amap)
        state (assoc amap :probe/level (str/upper-case (name level)))]
    (probe* ns form tags state)))

(defmacro trace
  [& keyvals]
  `(log-state (quote ~(ns-name *ns*)) (quote ~&form) :trace (vector ~@keyvals)))

(defmacro debug
  [& keyvals]
  `(log-state (quote ~(ns-name *ns*)) (quote ~&form) :debug (vector ~@keyvals)))

(defmacro info
  [& keyvals]
  `(log-state (quote ~(ns-name *ns*)) (quote ~&form) :info (vector ~@keyvals)))

(defmacro warn
  [& keyvals]
  `(log-state (quote ~(ns-name *ns*)) (quote ~&form) :warn (vector ~@keyvals)))

(defmacro error
  [& keyvals]
  `(log-state (quote ~(ns-name *ns*)) (quote ~&form) :error (vector ~@keyvals)))

;; Log translation utilities

(def ^:private log-df
  (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm.SSS")
    (.setTimeZone (java.util.TimeZone/getTimeZone "UTC"))))

(defn- human-date [ts]
  (when ts
    (str (.format log-df ts))))

(defn- log-vals
  [m]
  (map (fn [[key val]]
         (str (name key) "=\"" (str val) "\""))
       m))

(defn state->log-string
  [state]
  (let [new (dissoc state :probe/ts :probe/ns :probe/line :probe/tags :probe/level
                    :probe/ip :probe/host :probe/pid :probe/thread-id)]
    (->> (log-vals new)
         (concat (vector
                  (:probe/ip state)
                  (human-date (:probe/ts state))
                  (:probe/level state)
                  (when (:probe/ns state)
                    (str (name (:probe/ns state)) ":" (:probe/line state)))
                  (str "pid=" (:probe/pid state))
                  (str "thread=" (:probe/thread-id state))))
;         ((fn [arg] (println arg) arg))
         (remove nil?)
         (apply print-str))))

(defn add-static-host-info
  "Return a transducer that adds host IP from creation
   time to probe state"
  []
  (let [addr (java.net.InetAddress/getLocalHost)
        ip (.getHostAddress addr)
        host (.getHostName addr)
        pid (.pid (java.lang.ProcessHandle/current))]
    (map #(assoc % :probe/ip ip :probe/host host :probe/pid pid))))

(defn as-log-string
  "Transducer to produce a log string"
  []
  (map state->log-string))


