(ns dhall-clojure.in.parse
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [dhall-clojure.in.core :refer :all]))

(def grammar (slurp (io/resource "dhall.abnf")))

(def dhall-parser
  (insta/parser grammar
                :input-format :abnf
                :start :complete-expression
                :output-format :enlive))

(defn clean
  "Cut the names of the attrs of the tree
  TODO: save the meta!"
  [tree]
  (if (map? tree)
    {:c (mapv clean (:content tree))
     ;;:a (:attrs tree)
     :t (:tag tree)}
    tree))

(def parse (comp clean dhall-parser))

;;
;; Utils
;;
(declare expr)

(defn first-child-expr
  "Folds the current expression into its first child"
  [e]
  (expr (-> e :c first)))

(defn children?
  "True if there is more than one child"
  [e]
  (> (count (:c e)) 1))

(defn compact
  "Given a parse tree, it will compact all the text in it,
  and return a single string"
  [tree]
  (cond
    (map? tree) (apply str (mapv compact (:c tree)))
    (string? tree) tree
    (seqable? tree) (apply str (mapv compact tree))
    :else tree))

;;
;; Parse Tree -> Expression Tree
;;
(defmulti expr
  "Takes an enlive parse tree, and constructs a tree of
  objects implementing IExpr"
  :t)

;;
;; Rules that we eliminate as not needed
;;
(defmethod expr :complete-expression [e]
  (expr (-> e :c second)))

(defmethod expr :operator-expression [e]
  (first-child-expr e))

(defmethod expr :import-expression [e]
  (first-child-expr e))

;;
;; Useful rules start here
;;
(defmethod expr :expression [e]
  (let [first-tag (-> e :c first :t)
        children (:c e)]
    (case first-tag
      :lambda "TODO lambda"
      :if (->BoolIf
            (expr (nth children 1))
            (expr (nth children 3))
            (expr (nth children 5)))
      :let "TODO let"
      :forall "TODO forall"
      :operator-expression "TODO operator expr"
      :annotated-expression (expr (first children)))))

(defmethod expr :annotated-expression [e]
  (let [first-tag (-> e :c first :t)
        children (:c e)]
    (case first-tag
      :merge "TODO merge"
      :open-bracket "TODO open-bracket"
      :operator-expression (if (> (count children) 1)
                             (->Annot
                               (expr (first children))
                               (expr (nth children 2)))
                             (expr (first children))))))

(defmethod expr :reserved-raw [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :Bool-raw     (->BoolT)
      :Optional-raw (->OptionalT)
      :Natural-raw  (->NaturalT)
      :Integer-raw  (->IntegerT)
      :Double-raw   (->DoubleT)
      :Text-raw     (->TextT)
      :List-raw     (->ListT)
      :True-raw     (->BoolLit true)
      :False-raw    (->BoolLit false)
      :Type-raw     (->Const :type)
      :Kind-raw     (->Const :kind))))

(defmethod expr :reserved-namespaced-raw [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :Natural-fold-raw      (->NaturalFold)
      :Natural-build-raw     (->NaturalBuild)
      :Natural-isZero-raw    (->NaturalIsZero)
      :Natural-even-raw      (->NaturalEven)
      :Natural-odd-raw       (->NaturalOdd)
      :Natural-toInteger-raw (->NaturalToInteger)
      :Natural-show-raw      (->NaturalShow)
      :Integer-toDouble-raw  (->IntegerToDouble)
      :Integer-show-raw      (->IntegerShow)
      :Double-show-raw       (->DoubleShow)
      :List-build-raw        (->ListBuild)
      :List-fold-raw         (->ListFold)
      :List-length-raw       (->ListLength)
      :List-head-raw         (->ListHead)
      :List-last-raw         (->ListLast)
      :List-indexed-raw      (->ListIndexed)
      :List-reverse-raw      (->ListReverse)
      :Optional-fold-raw     (->OptionalFold)
      :Optional-build-raw    (->OptionalBuild))))

(defn identifier-with-reserved-prefix [e]
  (let [children (:c e)
        ;; the prefix is the reserved word
        prefix (->> children first :c first :c (apply str))
        ;; at the end of `children` there might be a DeBrujin index
        maybe-index (-> children butlast last)
        index? (= :whitespace (:t maybe-index))
        index (if index?
                0    ;; TODO: is i always 0?
                (-> maybe-index :c first :c first read-string))
        ;; the label is the rest of the chars
        label (->> children
                 rest
                 (drop-last (if index? 2 4))
                 compact)]
    (->Var (str prefix label) index)))

(defmethod expr :identifier-reserved-namespaced-prefix [e]
  (identifier-with-reserved-prefix e))

(defmethod expr :identifier-reserved-prefix [e]
  (identifier-with-reserved-prefix e))

(defmacro defexpr*
  "Generalize `defmethod` for the cases in which we need to do
  something like:
  - if there's one remove this tag
  - if there's multiple create an `Expr a b` and recur with left-precedence"
  [parser-tag record-class separator-tag]
  (let [expr-constructor (symbol (str "->" record-class))]
    `(defmethod expr ~parser-tag [e#]
       (if (> (count (:c e#)) 1)
         (let [exprs# (remove #(= ~separator-tag (:t %)) (:c e#))]
           (loop [more# (nnext exprs#)
                  start# (~expr-constructor
                           (expr (first exprs#))
                           (expr (second exprs#)))]
             (if (empty? more#)
               start#
               (recur (rest more#)
                      (~expr-constructor start# (expr (first more#)))))))
         (expr (-> e# :c first))))))


(defexpr* :import-alt-expression    ImportAlt    :import-alt)
(defexpr* :or-expression            BoolOr       :or)
(defexpr* :plus-expression          NaturalPlus  :plus)
(defexpr* :text-append-expression   TextAppend   :text-append)
(defexpr* :list-append-expression   ListAppend   :list-append)
(defexpr* :and-expression           BoolAnd      :and)
(defexpr* :combine-expression       Combine      :combine)
(defexpr* :prefer-expression        Prefer       :prefer)
(defexpr* :combine-types-expression CombineTypes :combine-types)
(defexpr* :times-expression         NaturalTimes :times)
(defexpr* :equal-expression         BoolEQ       :double-equal)
(defexpr* :not-equal-expression     BoolNE       :not-equal)

;; TODO: support `constructors`
(defexpr* :application-expression App :whitespace-chunk)

(defmethod expr :import [e]
  e) ;; TODO

(defmethod expr :selector-expression [e]
  (if (children? e)
    "TODO handle accessor fields"
    (first-child-expr e))) ;; Otherwise we go to the primitive expression

(defmethod expr :primitive-expression [e]
  (let [first-tag (-> e :c first :t)
        children (:c e)]
    (case first-tag
      :double-literal (-> children first compact read-string ->DoubleLit)
      :natural-literal (-> children first compact read-string ->NaturalLit)
      :integer-literal (-> children first compact read-string ->IntegerLit)
      :text-literal (-> children first expr)
      :open-brace (-> children second expr)
      :open-angle "TODO open-angle"
      :non-empty-list-literal (-> children first expr)
      :identifier-reserved-namespaced-prefix (-> children first expr)
      :reserved-namespaced (-> children first :c first expr) ;; returns a :reserved-namespaced-raw
      :identifier-reserved-prefix (-> children first expr)
      :reserved (-> children first :c first expr) ;; returns a :reserved-raw
      :identifier "TODO identifier"
      :open-parens "TODO open-parens")))

(defmethod expr :record-type-or-literal [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :equal                            (->RecordLit {}) ;; Empty record literal
      :non-empty-record-type-or-literal (-> e :c first expr)
      (->RecordT {}))))                                  ;; Empty record type

(defmethod expr :non-empty-record-type-or-literal [e]
  (let [first-label (-> e :c first expr)
        other-vals (-> e :c second)
        record-literal? (= (:t other-vals) :non-empty-record-literal)
        [first-val other-kvs] [(-> other-vals :c second expr)
                               (->> (-> other-vals :c (nthrest 2))
                                  (partition 4)
                                  (mapv (fn [[comma label sep expr']]
                                          {(expr label)
                                           (expr expr')}))
                                  (apply merge))]]
    ((if record-literal?
       ->RecordLit
       ->RecordT)
     (merge {first-label first-val} other-kvs))))

(defmethod expr :label [e]
  (let [quoted? (-> e :c first string?) ;; a quoted label is preceded by `
        actual-label ((if quoted? second first) (:c e))
        str-label (->> actual-label :c
                      (mapv (fn [ch]
                              (if (string? ch)
                                ch
                                (-> ch :c first))))
                      (apply str))]
    (if quoted?
      (str "`" str-label "`")
      str-label)))

(defmethod expr :non-empty-list-literal [e]
  ;; TODO: I guess here we'd need multi-arity to be able to pass
  ;; in the optional type of the list
  (let [vals (->> e :c rest (take-nth 2) (mapv expr))]
    (->ListLit nil vals)))

(defmethod expr :text-literal [e]
  (let [first-tag (-> e :c first :t)
        children (:c e)]
    (->TextLit
      (if (= first-tag :double-quote-literal)
        ;; If it's a double quoted string, we fold on the children,
        ;; so that we collapse the contiguous strings in a single chunk,
        ;; while skipping the interpolation expressions
        (loop [children (-> children first :c rest butlast) ;; Skip the quotes
               acc nil
               chunks []]
          (if (seq children)
            (let [chunk (first children)
                  content (:c chunk)]
              (if (every? string? content)  ;; If they are not strings, it's an interpolation
                (recur (rest children)
                       (str acc (apply str content))
                       chunks)
                (recur (rest children)
                       nil
                       (conj chunks acc (expr (nth content 1))))))
            ;; If we have no children left to process,
            ;; we return the chunks we have, plus the accomulator
            (if-not acc
              chunks
              (conj chunks acc))))
        ;; Otherwise it's a single quote literal,
        ;; so we recur over the children until we find an ending literal.
        ;; As above, we make expressions out of interpolation syntax
        (loop [children (-> children first :c second :c)
               acc nil
               chunks []]
          (if (= children ["''"])
            (if-not acc  ;; If we have chars left in acc
              chunks
              (conj chunks acc))
            (if (not= (first children) "${")  ;; Check if interpolation
              ;; If not we just add the string and recur
              (recur (-> children second :c)
                     (str acc (first children))
                     chunks)
              (recur (-> children (nth 3) :c)
                     nil
                     (conj chunks acc (expr (second children)))))))))))

;; Default case, we end up here when there is no matches
(defmethod expr :default [e]
  (println "Hitting default case")
  (println e)
  e)

