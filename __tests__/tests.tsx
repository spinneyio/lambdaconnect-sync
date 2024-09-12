import { log, error } from "console";

const { DOMParser, XMLSerializer } = require('@xmldom/xmldom');
global.DOMParser = DOMParser;
const { testCljs } = require("./cljs-tests.js");


describe('Clojurescript tests', () => {
  test('Clojurescript tests', () => {
    let { success, output } = testCljs();
    try {
        expect(success).toBeTruthy();
        log(output);            
    } catch (e) {
        throw new Error(output);
    }
  });
});