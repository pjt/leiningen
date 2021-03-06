(ns leiningen.pom
  "Write a pom.xml file to disk for Maven interop."
  (:use [clojure.java.io :only [reader copy file]]
        [clojure.contrib.properties :only [as-properties]])
  (:import [java.io StringWriter ByteArrayOutputStream]
           [org.apache.maven.model Build Model Parent Dependency
            Exclusion Repository Scm License MailingList Resource]
           [org.apache.maven.project MavenProject]))

(def #^{:doc "A notice to place at the bottom of generated files."} disclaimer
     "\n<!-- This file was autogenerated by the Leiningen build tool.
  Please do not edit it directly; instead edit project.clj and regenerate it.
  It should not be considered canonical data. For more information see
  http://github.com/technomancy/leiningen -->\n")

(defn read-git-ref
  "Reads the commit SHA1 for a git ref path."
  [git-dir ref-path]
  (.trim (slurp (str (file git-dir ref-path)))))

(defn read-git-head
  "Reads the value of HEAD and returns a commit SHA1."
  [git-dir]
  (let [head (.trim (slurp (str (file git-dir "HEAD"))))]
    (if-let [ref-path (second (re-find #"ref: (\S+)" head))]
      (read-git-ref git-dir ref-path)
      head)))

(defn read-git-origin
  "Reads the URL for the remote origin repository."
  [git-dir]
  (with-open [rdr (reader (file git-dir "config"))]
    (->> (map #(.trim %) (line-seq rdr))
         (drop-while #(not= "[remote \"origin\"]" %))
         (next)
         (take-while #(not (.startsWith % "[")))
         (map #(re-matches #"url\s*=\s*(\S*)\s*" %))
         (filter identity)
         (first)
         (second))))

(defn parse-github-url
  "Parses a GitHub URL returning a [username repo] pair."
  [url]
  (when url
    (next
     (or
      (re-matches #"(?:git@)?github.com:([^/]+)/([^/]+).git" url)
      (re-matches #"[^:]+://(?:git@)?github.com/([^/]+)/([^/]+).git" url)))))

(defn github-urls [url]
  (when-let [[user repo] (parse-github-url url)]
    {:public-clone (str "git://github.com/" user "/" repo ".git")
     :dev-clone (str "ssh://git@github.com/" user "/" repo ".git")
     :browse (str "http://github.com/" user "/" repo)}))

(defn make-git-scm [git-dir]
  (try
    (let [origin (read-git-origin git-dir)
          head (read-git-head git-dir)
          urls (github-urls origin)
          scm (Scm.)]
      (.setUrl scm (:browse urls))
      (.setTag scm head)
      (when (:public-clone urls)
        (.setConnection scm (str "scm:git:" (:public-clone urls))))
      (when (:dev-clone urls)
        (.setDeveloperConnection scm (str "scm:git:" (:dev-clone urls))))
      scm)
    (catch java.io.FileNotFoundException e
      nil)))

(defn make-exclusion [excl]
  (doto (Exclusion.)
    (.setGroupId (or (namespace excl) (name excl)))
    (.setArtifactId (name excl))))

(defn make-dependency
  "Makes a dependency from a seq. The seq (usually a vector) should
contain a symbol to define the group and artifact id, then a version
string. The remaining arguments are combined into a map. The value for
the :classifier key (if present) is the classifier on the
dependency (as a string). The value for the :exclusions key, if
present, is a seq of symbols, identifying group ids and artifact ids
to exclude from transitive dependencies."
  [[dep version & extras]]
  (let [extras-map (apply hash-map extras)
        exclusions (:exclusions extras-map)
        classifier (:classifier extras-map)
        es (map make-exclusion exclusions)]
    (doto (Dependency.)
      ;; Allow org.clojure group to be omitted from clojure/contrib deps.
      (.setGroupId (if (and (nil? (namespace dep))
                            (re-find #"^clojure(-contrib)?$" (name dep)))
                     "org.clojure"
                     (or (namespace dep) (name dep))))
      (.setArtifactId (name dep))
      (.setVersion version)
      (.setClassifier classifier)
      (.setExclusions es))))

(defn make-repository [[id settings]]
  (let [repo (Repository.)]
    (.setId repo id)
    (if (string? settings)
      (.setUrl repo settings)
      (.setUrl repo (:url settings)))
    repo))

(defn make-license [{:keys [name url distribution comments]}]
  (doto (License.)
    (.setName name)
    (.setUrl url)
    (.setDistribution (and distribution (clojure.core/name distribution)))
    (.setComments comments)))

(defn make-mailing-list [{:keys [name archive other-archives
                                 post subscribe unsubscribe]}]
  (let [mailing-list (MailingList.)]
    (doto mailing-list
      (.setName name)
      (.setArchive archive)
      (.setPost post)
      (.setSubscribe subscribe)
      (.setUnsubscribe unsubscribe))
    (doseq [other-archive other-archives]
      (.addOtherArchive mailing-list other-archive))
    mailing-list))

(def default-repos {"central" "http://repo1.maven.org/maven2"
                    "clojure" "http://build.clojure.org/releases"
                    "clojure-snapshots" "http://build.clojure.org/snapshots"
                    "clojars" "http://clojars.org/repo/"})

(defn relative-path
  [project path-key]
  (.replace (path-key project) (str (:root project) "/") ""))

(defmacro add-a-resource [build method resource-path]
  `(let [resource# (Resource.)]
     (.setDirectory resource# ~resource-path)
     (~(symbol (name method)) ~build [resource#])))

(defn make-model [project]
  (let [model (doto (Model.)
                (.setModelVersion "4.0.0")
                (.setArtifactId (:name project))
                (.setName (:name project))
                (.setVersion (:version project))
                (.setGroupId (:group project))
                (.setDescription (:description project))
                (.setUrl (:url project)))
        build (doto (Build.)
                (add-a-resource :.setResources
                                (relative-path project :resources-path))
                (add-a-resource :.setTestResources
                                (relative-path project :test-resources-path))
                (.setSourceDirectory (relative-path project :source-path))
                (.setTestSourceDirectory (relative-path project :test-path)))]
    (.setBuild model build)
    (doseq [dep (:dependencies project)]
      (.addDependency model (make-dependency dep)))
    (doseq [repo (concat (:repositories project) default-repos)]
      (.addRepository model (make-repository repo)))
    (when-let [scm (make-git-scm (file (:root project) ".git"))]
      (.setScm model scm))
    (doseq [license (concat (keep #(% project)
                                  [:licence :license])
                            (:licences project)
                            (:licenses project))]
      (.addLicense model (make-license license)))
    (doseq [mailing-list (concat (if-let [ml (:mailing-list project)] [ml] [])
                                 (:mailing-lists project))]
      (.addMailingList model (make-mailing-list mailing-list)))
    model))

(defn make-pom
  ([project] (make-pom project false))
  ([project disclaimer?]
     (with-open [w (StringWriter.)]
       (.writeModel (MavenProject. (make-model project)) w)
       (when disclaimer?
         (.write w disclaimer))
       (.getBytes (str w)))))

(defn make-pom-properties [project]
  (with-open [baos (ByteArrayOutputStream.)]
    (.store (as-properties {:version (:version project)
                            :groupId (:group project)
                            :artifactId (:name project)})
            baos "Leiningen")
    (.getBytes (str baos))))

(defn pom
  "Write a pom.xml file to disk for Maven interop."
  ([project pom-location silently?]
     (let [pom-file (file (:root project) pom-location)]
       (copy (make-pom project true) pom-file)
       (when-not silently? (println "Wrote" (.getName pom-file)))
       (.getAbsolutePath pom-file)))
  ([project pom-location] (pom project pom-location false))
  ([project] (pom project "pom.xml")))
