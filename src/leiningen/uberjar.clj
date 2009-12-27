(ns leiningen.uberjar
  "Create a jar containing the compiled code, source, and all dependencies."
  (:require [clojure.xml :as xml]
            [clojure.contrib.str-utils2 :as s])
  (:use [clojure.zip :only [xml-zip]]
        [clojure.contrib.java-utils :only [file]]
        [clojure.contrib.duck-streams :only [copy]]
        [clojure.contrib.zip-filter.xml :only [xml-> tag=]]
        [leiningen.jar :only [jar]])
  (:import [java.util.zip ZipFile ZipOutputStream ZipEntry]
           [java.io File FileOutputStream PrintWriter]))

(defn read-components [zipfile]
  (when-let [entry (.getEntry zipfile "META-INF/plexus/components.xml")]
    (-> (xml-> (xml-zip (xml/parse (.getInputStream zipfile entry)))
               (tag= :components))
        first first :content)))

(defn copy-entries
  "Copies the entries of ZipFile in to the ZipOutputStream out, skipping
  the entries which satisfy skip-pred. Returns the names of the
  entries copied."
  [in out & [skip-pred]]
  (for [file (enumeration-seq (.entries in))
        :when (not (skip-pred file))]
    (do
      (.setCompressedSize file -1) ; some jars report size incorrectly
      (.putNextEntry out file)
      (copy (.getInputStream in file) out)
      (.closeEntry out)
      (.getName file))))

;; we have to keep track of every entry we've copied so that we can
;; skip duplicates.  We also collect together all the plexus components so
;; that we can merge them.
(defn include-dep [out [skip-set components] dep]
  (println "Including" (.getName dep))
  (with-open [zipfile (ZipFile. dep)]
    [(into skip-set (copy-entries zipfile out #(skip-set (.getName %))))
     (concat components (read-components zipfile))]))


(defn dep-jar-regex
  "Given dependency as vector of symbol ('org.clojure/clojure) & version, return 
  regex that matches its jar."
  [[dep-sym version]]
  (let [artifact  (second (s/split (str dep-sym) #"/"))
        version   (s/replace version "SNAPSHOT" "\\d{8}\\.[\\d-]+")]
    (re-pattern (str artifact "-" version "\\.jar"))))

(defn uberjar*
  "Create a jar like the jar task, but include the contents of each of
   the dependency jars, minus those jars that satisfy the exclude? predicate."
  [project exclude?]
  (jar project)
  (with-open [out (-> (file (:root project)
                            (str (:name project) "-standalone.jar"))
                      (FileOutputStream.) (ZipOutputStream.))]
    (let [deps (->> (file-seq (file (:library-path project)))
                    (filter #(.endsWith (.getName %) ".jar"))
                    (remove exclude?)
                    (cons (file (:root project) (str (:name project) ".jar"))))
          [_ components] (reduce (partial include-dep out)
                                 [#{"META-INF/plexus/components.xml"} nil]
                                 deps)]
      (when-not (empty? components)
        (.putNextEntry out (ZipEntry. "META-INF/plexus/components.xml"))
        (binding [*out* (PrintWriter. out)]
          (xml/emit {:tag :component-set
                     :content
                     [{:tag :components
                       :content
                       components}]})
          (.flush *out*))
        (.closeEntry out)))))


(defn uberjar
  "Create a jar like the jar task, but including the contents of each of
the dependency jars. Suitable for standalone distribution."
  [project]
  (let [dev-regexes (map dep-jar-regex (:dev-dependencies project))
        exclude?    (fn [jar] (some #(re-find % (.getName jar)) dev-regexes))]
    (uberjar* project exclude?)))

