(ns bosquet.db.qdrant
  (:require
   [bosquet.db.vector-db :as vdb]
   [bosquet.env :as env]
   [bosquet.utils :as utils]
   [clojure.walk :as walk]
   [hato.client :as hc]
   [jsonista.core :as j]))

(def ^:private qdrant-endpoint
  (env/val :qdrant :api-endpoint))


(defn collections
  "URL endpoint for collection with the `name` operations."
  [name]
  (str  qdrant-endpoint "/collections/" name))


(defn collection-info
  [collection-name]
  (let [{:keys [body status]} (hc/get (collections collection-name)
                                      {:throw-exceptions? false})]
    (condp = status
      200 (-> body (j/read-value j/keyword-keys-object-mapper) :result)
      404 nil)))

(defn create-collection
  "Create a collection with `name` and `config`"
  [{:keys [vectors-on-disk vectors-size vectors-distance]} collection-name]
  (hc/put (str  qdrant-endpoint "/collections/" collection-name)
          {:content-type :json
           :body         (j/write-value-as-string
                          {:vectors {:size     vectors-size
                                     :distance vectors-distance
                                     :on_disk  vectors-on-disk}})}))

(defn delete-collection
  [collection-name]
  (hc/delete (str qdrant-endpoint "/collections/" collection-name)))

(defn add-docs
  "Add docs to the `collection-name` if collection does not exist, create it."
  [collection-name data]
  (when-not (collection-info collection-name)
    (create-collection collection-name (env/val :qdrant)))

  (let [points {:points (mapv (fn [{:keys [payload embedding]}]
                                {:id      (utils/uuid)
                                 :vector  embedding
                                 :payload payload})
                              data)}]
    (hc/put (format "%s/collections/%s/points?wait=true"
                    qdrant-endpoint collection-name)
            {:content-type :json
             :body         (j/write-value-as-string points)})))

(defn search
  ([collection-name embeds-vector]
   (search collection-name embeds-vector 3))
  ([collection-name embeds-vector top-n]
   (let [res (-> (format "%s/collections/%s/points/search" qdrant-endpoint collection-name)
                 (hc/post
                  {:content-type :json
                   :body         (j/write-value-as-string
                                  {:vector       embeds-vector
                                   :top          top-n
                                   :with_payload true})})
                 :body
                 j/read-value
                 (get "result")
                 (walk/keywordize-keys))]
     (map #(select-keys % [:id :score :payload]) res))))

(deftype Qdrant
         [opts]
  vdb/VectorDB
  (create [_this collection-name]
    (create-collection collection-name opts))
  (delete [_this collection-name]
    (delete-collection collection-name))
  (add [_this collection-name docs]
    (add-docs collection-name docs))
  (search [_this collection-name embeddings limit]
    (search collection-name embeddings limit)))
