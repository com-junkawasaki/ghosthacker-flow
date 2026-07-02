(ns ghosthacker-flow.demo-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ghosthacker-flow.demo :as demo]))

(deftest main-smoke-test
  (let [output (with-out-str (demo/-main))]
    (is (str/includes? output "GHOST HACKER: FLOW"))
    (is (str/includes? output "grade="))
    (is (str/includes? output "easy"))
    (is (str/includes? output "hard"))))
