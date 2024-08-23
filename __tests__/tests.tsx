import { log, error } from "console";

const { DOMParser, XMLSerializer } = require('@xmldom/xmldom');
global.DOMParser = DOMParser;
const { test_cljs } = require("./cljs_tests.js");


describe('Clojurescript tests', () => {
  test('Clojurescript tests', () => {
    let { success, output } = test_cljs();
    try {
        expect(success).toBeTruthy();
        log(output);            
    } catch (e) {
        throw new Error(output);
    }
  });
});