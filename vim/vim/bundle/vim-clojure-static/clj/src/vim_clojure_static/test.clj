;; Authors: Sung Pae <self@sungpae.com>

(ns vim-clojure-static.test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as test]))

(defn syn-id-names
  "Map lines of clojure text to vim synID names at each column as keywords:

   (syn-id-names \"foo\" …) -> {\"foo\" [:clojureString :clojureString :clojureString] …}

   First parameter is the file that is used to communicate with Vim. The file
   is not deleted to allow manual inspection."
  [file & lines]
  (io/make-parents file)
  (spit file (string/join \newline lines))
  (shell/sh "vim" "-u" "NONE" "-N" "-S" "vim/syn-id-names.vim" file)
  ;; The last line of the file will contain valid EDN
  (into {} (map (fn [l ids] [l (mapv keyword ids)])
                lines
                (edn/read-string (peek (string/split-lines (slurp file)))))))

(defn subfmt
  "Extract a subsequence of seq s corresponding to the character positions of
   %s in format spec fmt"
  [fmt s]
  (let [f (seq (format fmt \o001))
        i (.indexOf f \o001)]
    (->> s
         (drop i)
         (drop-last (- (count f) i 1)))))

(defmacro defsyntaxtest
  "Create a new testing var with tests in the format:

   (defsyntaxtest example
     (with-format \"#\\\"%s\\\"\"
       \"123\" #(every? (partial = :clojureRegexp) %)
       …)
     (with-format …))

   At runtime the syn-id-names of the strings (which are placed in the format
   spec) are passed to their associated predicates. The format spec should
   contain a single `%s`."
  [name & body]
  (assert (every? #(= 'with-format (first %)) body))
  (assert (every? #(string? (second %)) body))
  (assert (every? #(even? (count %)) body))
  (let [[strings contexts] (reduce (fn [[strings contexts] [_ fmt & forms]]
                                     (let [[ss λs] (apply map list (partition 2 forms))
                                           ss (map #(format fmt %) ss)]
                                       [(concat strings ss)
                                        (conj contexts {:fmt fmt :ss ss :λs λs})]))
                                   [[] []] body)
        syntable (gensym "syntable")]
    `(test/deftest ~name
       ;; Shellout to vim should happen at runtime
       (let [~syntable (syn-id-names (str "tmp/" ~(str name) ".clj") ~@strings)]
         ~@(map (fn [{:keys [fmt ss λs]}]
                  `(test/testing ~fmt
                     ~@(map (fn [s λ] `(test/is (~λ (subfmt ~fmt (get ~syntable ~s)))))
                            ss λs)))
                contexts)))))

(comment

  (macroexpand-1
    '(defsyntaxtest number-literals-test
       (with-format "%s"
         "123" #(every? (partial = :clojureNumber) %)
         "456" #(every? (partial = :clojureNumber) %))
       (with-format "#\"%s\""
         "^" #(= % [:clojureRegexpBoundary]))))
  (test #'number-literals-test)

  )
