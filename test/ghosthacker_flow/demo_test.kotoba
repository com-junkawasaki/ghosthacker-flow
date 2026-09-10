(ns ghosthacker-flow.demo-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.lang.text :as str]
            [ghosthacker-flow.demo :as demo]))

(deftest main-smoke-test
  (let [output (with-out-str (demo/-main))]
    (is (str/includes? output "GHOST HACKER: FLOW"))
    (is (str/includes? output "grade="))
    (is (str/includes? output "easy"))
    (is (str/includes? output "hard"))
    (is (str/includes? output "groove="))
    (is (str/includes? output "beat1 "))))
