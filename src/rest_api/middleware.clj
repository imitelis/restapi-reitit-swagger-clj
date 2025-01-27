(ns rest-api.middleware
  (:require [clojure.walk :refer [postwalk]]
            [camel-snake-kebab.core :as csk]))

(def default-options
  "Default case letter: request is send in camelCase and converted to kebab-case"
  {:from :camelCase
   :to   :kebab-case})

(defn convert-letter-case
  "(safely) convert key using coerce function"
  [coerce-fn k]
  (cond
    (nil? k)     k
    (string? k)  (coerce-fn k)
    (keyword? k) (coerce-fn k)
    (number? k)  (coerce-fn (str k))
    (boolean? k) (coerce-fn (str k))
    :else        (keyword k)))

(defn letter-case-keyword
  [k letter-case]
  (case letter-case
    :PascalCase           (convert-letter-case csk/->PascalCaseKeyword k)
    :camelCase            (convert-letter-case csk/->camelCaseKeyword k)
    :SCREAMING_SNAKE_CASE (convert-letter-case csk/->SCREAMING_SNAKE_CASE_KEYWORD k)
    :snake_case           (convert-letter-case csk/->snake_case_keyword k)
    :kebab-case           (convert-letter-case csk/->kebab-case-keyword k)
    :Camel_Snake_Case     (convert-letter-case csk/->Camel_Snake_Case_Keyword k)

    (keyword k)))

(defn filter-keys-letter-case
  "Recursively filter `letter-case` map keys"
  [m letter-case]
  (let [f (fn [m [k v]]
            (if (= (keyword k) (letter-case-keyword k letter-case))
              (assoc m k v)
              m))]
    (postwalk (fn [x] (if (map? x) (reduce f {} x) x)) m)))

(defn transform-keys-letter-case
  [m letter-case]
  (let [f (fn [[k v]] [(letter-case-keyword k letter-case) v])]
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn transform-request-params
  "transform request body and query parameters letter case.To support both
  ring `params-request` and `wrap-json-body` and reitit coercion middlewares,
  transform the request body\\params and body-params\\query-params"
  [request options]
  (let [from-letter-case (or (:from options) (:from default-options))
        to-letter-case   (or (:to options) (:to default-options))]
    (reduce (fn [req k]
              (if (coll? (k req))
                (update req k
                        #(transform-keys-letter-case
                          (filter-keys-letter-case % from-letter-case)
                          to-letter-case))
                req))
            request
            [:body-params :query-params :body :params])))

(defn letter-case-request
  "Middleware to recursively transforms all request body and query params keys.

  Accept the following options:

  :from - convert keys FROM letter case (default :camelCase)
  :to   - convert keys TO letter case (default :kebab-case)

  letter case keywords:
    :PascalCase
    :camelCase
    :SCREAMING_SNAKE_CASE
    :snake_case
    :kebab-case
    :Camel_Snake_Case

  This middleware must be called AFTER request params were parsed"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (fn ([request]
       (handler (transform-request-params request options)))
    ([request respond raise]
     (handler
      (transform-request-params request options)
      respond
      raise))))

(defn letter-case-response
  "Middleware for recursively transforms all response body keys letter case

  Accept the following options:

  :to - convert keys TO letter case (default :camelCase)

  letter case keywords:
    :PascalCase
    :camelCase
    :SCREAMING_SNAKE_CASE
    :snake_case
    :kebab-case
    :Camel_Snake_Case

  This middleware must be called BEFORE response body was serialized"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (let [to-letter-case (or (:to options) (:to default-options))]
    (fn
      ([request]
       (let [response (handler request)]
         (if (coll? (:body response))
           (update response
                   :body
                   #(transform-keys-letter-case
                     % to-letter-case))
           response)))
      ([request respond raise]
       (handler
        request
        (fn [response]
          (respond (if (coll? (:body response))
                     (update response
                             :body
                             #(transform-keys-letter-case
                               % to-letter-case))
                     response)))
        raise)))))

(defn letter-case-swagger-body
  "Transforms swagger.json response body params values to letter case"
  [body letter-case]
  (let [f (fn [[k v]]
            (case k
              :required (if (vector? v)
                          [k (mapv #(letter-case-keyword % letter-case) v)]
                          [k v])
              :name     (if (keyword? v)
                          [k (letter-case-keyword v letter-case)]
                          [k v])
              [k v]))]
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) body)))

(defn letter-case-swagger-response
  "Middleware for transform swagger.json reponse parameters to letter case

   Accept the following options:

  :to - convert keys TO letter case (default :camelCase)

  letter case keywords:
    :PascalCase
    :camelCase
    :SCREAMING_SNAKE_CASE
    :snake_case
    :kebab-case
    :Camel_Snake_Case

  This middleware wrap reitit.swagger/create-swagger-handler function"
  [handler & [{:as options}]]
  (let [to-letter-case (or (:to options) (:to default-options))]
    (fn
      ([request]
       (let [response (handler request)]
         (update response
                 :body
                 #(letter-case-swagger-body % to-letter-case))))
      ([request respond raise]
       (handler
        request
        (fn [response]
          (respond
           (update response
                   :body
                   #(letter-case-swagger-body % to-letter-case))))
        raise)))))

(defn check-authorization
  [request]
  true)

(defn authorize-middleware [handler]
  (fn [request]
    (let [authorized? (check-authorization request)]
      (if authorized?
        (handler request)
        {:status 401
         :body "Unauthorized"}))))