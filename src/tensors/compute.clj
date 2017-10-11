(ns tensors.compute
  (:require [tensors.computation-graph :as cg]
            [tensors.core :as tensors]
            [tensors.graph :as graph]
            [plumbing.core :as p]
            [schema.core :as s]
            [clojure.set :as set]
            [tensors.model :as model]
            [tensors.cache-pool :as cache-pool])
  (:import [tensors.node Node]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;  Compiled Graph Protocols + Operations

(defprotocol TensorOp
  (ensure-valid?! [this input-nodes]
    "Ensure the operation can be perfed with the tensor operation. Some
    imp0lemntations may support limited dimension or sizes")
  (prep [this node]
    "add any tensor fields useful to share between forward/backwward calls")
  (forward-node-pass! [this node]
    "compute the forward pass of the algorithm, for each node, compute
     `:value` tensor for passed in node, using the `:children` nodes
     and their `:value` tensors. Returns the node in case any other
     computations are added to the node for use in the backward pass.")
  (backward-node-pass! [this node]
    "compute the `:grad` gradient tensor on each child of passed in node reaching
     down to the leaves  (which include the parameter nodes).
     Returns the node so that downstream backward-node-pass!
     calls can use added data."))

(defprotocol BatchTensorOp
  (batch-signature [this node]
    "signature that can be used to group operations")
  (batch-forward-node-pass! [this sig nodes]
    "compute the batch version of the forward pass")
  (batch-backward-node-pass! [this sig nodes]
    "compute the batch version of the backward pass"))

(defn -trivial-batch-forward-pass! [tensor-op nodes]
  (mapv
   (fn [n] (forward-node-pass! tensor-op n))
   nodes))

(defn -trivial-batch-backward-pass! [tensor-op nodes]
  (mapv
   (fn [n] (backward-node-pass! tensor-op n))
   nodes))

(s/defn ensure-tensor-op
  "valdiates that tensor op valid for a computation,
   delegates down to `TensorOp` itself via `TensorFactory`"
  [factory :- tensors/PFactory
   result-node  :- Node
   arg-nodes :- [Node]]
  (let [op-key (-> result-node .graph-op cg/op-key)
        tensor-op (tensors/get-op factory op-key)]
    (ensure-valid?! tensor-op arg-nodes)
    tensor-op))

(defmacro key-case [k & clauses]
  `(cond
     ~@(mapcat (fn [[ok# v#]] (list (list 'clojure.lang.Util/identical k ok#) v#))
               (partition 2 clauses))
     :else (throw (ex-info "No matching keyword" {:key ~k}))))

(defn return-key [key]
  (key-case key :value ::return-value :grad ::return-grad))

(def +zero+ (Double. 0.0))

(defn ensure-tensor! [^Node node key factory]
  (let [cache (-> factory meta :cache)
        [t return-fn] (cache-pool/get-obj cache (.shape node))
        return-fn #(return-fn t)]
    (when (identical? key :grad)
      (tensors/fill! factory t +zero+))
    (-> node
        (assoc key t)
        (with-meta (assoc (meta node) (return-key key) return-fn)))))

(defn release-tensor! [node key]
  (when-let [return-fn (-> node meta (get (return-key key)))]
    (return-fn)))

(defn with-tensors [^Node node model]
  (let [factory (model/tensor-factory model)]
    (key-case (.type node)
              ;; must create a new vlaue
              :input (ensure-tensor! node :value factory)
              :constant node
              ;; re-use the model values
              :params (model/canonical-node model (.ref-name node))
              ;; new values + grad
              :op (-> node
                      (ensure-tensor! :value factory)
                      (ensure-tensor! :grad factory)))))

(defn with-tensor-op [^Node node factory]
  (if (identical? (.type node) :op)
    (let [tensor-op (ensure-tensor-op factory node (.children node))]
      (assoc (prep tensor-op node)
             :tensor-op tensor-op))
    node))

(defn -compile-hack [^Node node factory input->vals model]
  (let [^Node node  (-> node
                        (with-tensors model)
                        (with-tensor-op factory))]
    (when (identical? :input (.type node))
      (let [vals (p/safe-get input->vals (.ref-name node))]
        (assert (.value node))
        (tensors/copy-from-input! factory (.value node) vals)))
    node))

(defn validate-input-keys [nodes ^java.util.Map input->vals]
  (let [provided (.keySet input->vals)]
    (doseq [^Node n nodes  :when (identical? :input (.type n))]
      (when-not (.contains provided (.ref-name n))
        (throw (ex-info "Missing required key" {:key (.ref-name n)}))))))

(defn -forward-intrnal [^Node node]
  (if-not (seq (.children node))
    ;; leaf node has no computation
    node
    ;; op node, fetch tensor-op
    ;; execute forward computation
    (let [tensor-op (.tensor-op node)
          forward-node (forward-node-pass! tensor-op node)]
      forward-node)))

(defn forward-pass!
  "forward-pass will topographic walk through graph writing to `:value`
  key on all compiled nodes. You can then look up and retrieve the tensors
  associated with any node"
  ([^Node target model] (forward-pass! target model {}))
  ([^Node target model input->vals]
   (let [nodes (graph/post-order-nodes target)
         factory (model/tensor-factory model)
         computed-nodes (java.util.HashMap. (count nodes))]
     (validate-input-keys nodes input->vals)
     ;; Copy input values to node tensors
     (graph/bottom-up-walk
      target
      (fn walk-fn [^Node node]
        (if-let [computed (.get computed-nodes (.ref-name node))]
          computed
          (let [^Node node (-compile-hack node factory input->vals model)
                ^Node node (-forward-intrnal node)]
            (.put computed-nodes (.ref-name node) node)
            node)))))))

(defn backward-pass!
  "backward-pass through all the parameter nodes associated with
   the graph computation, will write to `:grad` key for all nodes
   that have gradients (basically non-inputs) in graph"
  [target]
  (let [nodes (reverse (graph/post-order-nodes target))]
    (doseq [^Node n nodes :when (identical? :op (.type n))]
      (backward-node-pass! (.tensor-op n) n))
    (doseq [^Node n nodes]
      (key-case (.type n)
                ;; must create a new vlaue
                :input (release-tensor! n :value)
                :constant nil
                ;; new values + grad
                :op (do
                      (release-tensor! n :value)
                      (release-tensor! n :grad))
                :params nil))))
