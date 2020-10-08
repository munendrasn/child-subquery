package com.munendrasn.transformer;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.BinaryQueryResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.hamcrest.core.StringContains.containsString;


public class TestChildSubQueryAugmenter extends SolrTestCaseJ4 {

    private static Random myRandom;
    private static final String HandlerName = "/select";
    private static int maxID = 100;
    private static int maxChildren = 10;
    private static String numFoundAssertion = toAssertion(maxID);
    private static String solrHome = Thread.currentThread().getContextClassLoader().getResource("").getPath();

    private static String[] brand = {"Levis", "Lee", "Tommy Hiflinger", "Wrangler", "Max"};
    private static String[] color = {"Red", "Blue", "Green", "Navy", "Purple"};
    private static String IDField = "id";

    @BeforeClass
    public static void startCore() throws Exception {
        System.setProperty("tests.uniqueKey.name", "id");
        initCore("solrconfig.xml", "schema.xml", solrHome, "unit-test");
        myRandom = new Random(random().nextLong());
        createIndex();
    }

    @Test
    @Repeat(iterations = 2)
    public void testChildQuery() throws Exception {
        ignoreException("SolrException");

        // number of products
        assertQ(req("q", "parent_b:true"), numFoundAssertion);

        SolrException ex = expectThrows(SolrException.class, () ->{
                    h.query(req("q", "parent_b:true", "fl", "*,child:[childquery]"));
        });
        assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex.code());
        assertThat(ex.getMessage(), containsString("parentFilter is missing for childquery 'child'"));

        ex = expectThrows(SolrException.class, () ->{
            h.query(req("q", "parent_b:true", "fl", "*,[childquery]"));
        });
        assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex.code());
        assertThat(ex.getMessage(), containsString("please give an explicit name for [childquery] column ie fl=relation:[childquery ..]"));

        // with child rows parameter
        SolrQueryRequest solrQueryRequest = req("q", "parent_b:true", "fl", "*,child" +
                        ":[childquery]", "child.parentFilter", "parent_b:true", "wt", "javabin",
                "child.rows", "2");
        SolrQueryResponse queryResponse = h.queryAndResponse(HandlerName, solrQueryRequest);
        SolrDocumentList resultDocs = getSolrDocumentList(solrQueryRequest, queryResponse);
        for (SolrDocument doc: resultDocs) {
            assertTrue(doc.containsKey("child"));
            SolrDocumentList childList = (SolrDocumentList) doc.get("child");
            String uniqueId = (String) doc.getFieldValue(IDField);
            assertTrue(2>=childList.size());
            for (SolrDocument childDoc: childList) {
                assertEquals(uniqueId, childDoc.get("_root_"));
            }
        }

        // with filter (collapse filter)
        solrQueryRequest = req("q", "parent_b:true", "fl", "*,child" +
                        ":[childquery]", "child.parentFilter", "parent_b:true", "wt", "javabin",
                "child.fq", "{!collapse field=_root_}");
        SolrQueryResponse rsp = new SolrQueryResponse();
        SolrRequestInfo.setRequestInfo(new SolrRequestInfo(solrQueryRequest, rsp));
        queryResponse = h.queryAndResponse(HandlerName, solrQueryRequest);
        resultDocs = getSolrDocumentList(solrQueryRequest, queryResponse);
        for (SolrDocument doc: resultDocs) {
            assertTrue(doc.containsKey("child"));
            SolrDocumentList childList = (SolrDocumentList) doc.get("child");
            String uniqueId = (String) doc.getFieldValue(IDField);
            assertEquals(1, childList.getNumFound());
            for (SolrDocument childDoc: childList) {
                assertEquals(uniqueId, childDoc.get("_root_"));
            }
        }

        // with edismax and bf and fl and additional childquery
        solrQueryRequest = req("q", "parent_b:true", "fl", "*,score,child" +
                        ":[childquery],bleh:[childquery]", "child.parentFilter", "parent_b:true", "wt", "javabin",
                "child.q", "{!edismax v=parent_b:false}", "child.bf", "100", "child.fl", "score,_root_",
                "child.qf", "test_s","bleh.parentFilter", "parent_b:true", "bleh.fl", "*,score");
        rsp = new SolrQueryResponse();
        SolrRequestInfo.clearRequestInfo();
        SolrRequestInfo.setRequestInfo(new SolrRequestInfo(solrQueryRequest, rsp));
        queryResponse = h.queryAndResponse(HandlerName, solrQueryRequest);
        resultDocs = getSolrDocumentList(solrQueryRequest, queryResponse);
        for (SolrDocument doc: resultDocs) {
            assertTrue(doc.containsKey("child"));
            SolrDocumentList childList = (SolrDocumentList) doc.get("child");
            String uniqueId = (String) doc.getFieldValue(IDField);
            for (SolrDocument childDoc: childList) {
                assertEquals(uniqueId, childDoc.get("_root_"));
                // to taken in account query norm
                assertTrue(50.0f<=((Number)childDoc.get("score")).floatValue());
            }
            // check for other list
            SolrDocumentList blehList = (SolrDocumentList) doc.get("bleh");
            uniqueId = (String) doc.getFieldValue(IDField);
            for (SolrDocument childDoc: blehList) {
                assertEquals(uniqueId, childDoc.get("_root_"));
                assertTrue(100.0f>=((Number)childDoc.get("score")).floatValue());
            }
        }

        // real-time get
        solrQueryRequest = req("ids", "1", "fl", "*,score,child" +
                        ":[childquery],bleh:[childquery]", "child.parentFilter", "parent_b:true", "wt", "javabin",
                "child.q", "{!edismax v=parent_b:false}", "child.bf", "100", "child.fl", "score,_root_",
                "child.qf", "test_s","bleh.parentFilter", "parent_b:true", "bleh.fl", "*,score", "qt", "/get");
        rsp = new SolrQueryResponse();
        SolrRequestInfo.clearRequestInfo();
        SolrRequestInfo.setRequestInfo(new SolrRequestInfo(solrQueryRequest, rsp));
        queryResponse = h.queryAndResponse("/get", solrQueryRequest);
        resultDocs = getSolrDocumentList(solrQueryRequest, queryResponse);
        assertEquals(1, resultDocs.size());
        for (SolrDocument doc: resultDocs) {
            assertTrue(doc.containsKey("child"));
            SolrDocumentList childList = (SolrDocumentList) doc.get("child");
            String uniqueId = (String) doc.getFieldValue(IDField);
            for (SolrDocument childDoc: childList) {
                assertEquals(uniqueId, childDoc.get("_root_"));
                // to taken in account query norm
                assertTrue(50.0f<=((Number)childDoc.get("score")).floatValue());
            }
            // check for other list
            SolrDocumentList blehList = (SolrDocumentList) doc.get("bleh");
            uniqueId = (String) doc.getFieldValue(IDField);
            for (SolrDocument childDoc: blehList) {
                assertEquals(uniqueId, childDoc.get("_root_"));
                assertTrue(100.0f>=((Number)childDoc.get("score")).floatValue());
            }
        }

        // real-time get after update
        SolrInputDocument sdoc = sdoc(IDField, "1", "brand_s", random(brand),
                "color_s", random(color),
                "parent_b", "true",
                "price_f", Float.toString(myRandom.nextInt(10000))
        );
        SolrInputDocument childDoc = sdoc(IDField, "1" + "_" + "1000",
                "v_brand_s", "Bleh",
                "v_color_s", "Maroon",
                "parent_b", "false",
                "v_price_f", Float.toString(myRandom.nextInt(100))
        );
        for(String fieldName: sdoc.keySet()) {
            if(!fieldName.equals(IDField) && !fieldName.equals("parent_b"))
                childDoc.addField(fieldName, sdoc.getFieldValue(fieldName));
        }
        sdoc.addChildDocument(childDoc);
        updateJ(jsonAdd(sdoc), null);
        solrQueryRequest = req("ids", "1", "fl", "*,score,child" +
                        ":[childquery]", "child.parentFilter", "parent_b:true", "wt", "javabin",
                "child.q", "{!edismax v=parent_b:false}", "child.bf", "100", "child.fl", "*,score,_root_",
                "child.qf", "test_s", "qt", "/get");
        rsp = new SolrQueryResponse();
        SolrRequestInfo.clearRequestInfo();
        SolrRequestInfo.setRequestInfo(new SolrRequestInfo(solrQueryRequest, rsp));
        queryResponse = h.queryAndResponse("/get", solrQueryRequest);
        resultDocs = getSolrDocumentList(solrQueryRequest, queryResponse);
        assertEquals(1, resultDocs.size());
        for (SolrDocument doc: resultDocs) {
            assertTrue(doc.containsKey("child"));
            SolrDocumentList childList = (SolrDocumentList) doc.get("child");
            String uniqueId = (String) doc.getFieldValue(IDField);
            for (SolrDocument child: childList) {
                assertEquals(uniqueId, child.get("_root_"));
                assertEquals("1_1000", child.get(IDField));
                assertEquals("Maroon", child.get("v_color_s"));
                // to taken in account query norm
                assertTrue(50.0f<=((Number)child.get("score")).floatValue());
            }
        }
        assertU(commit());

        resetExceptionIgnores();
    }

    private static void createIndex() throws Exception {
        List<SolrInputDocument> solrInputDocuments = new LinkedList<>();
        for (int i = 0; i < maxID; i++) {
            String uniqueID = Integer.toHexString(i);
            SolrInputDocument sdoc = sdoc(IDField, uniqueID, "brand_s", random(brand),
                    "color_s", random(color),
                    "parent_b", "true",
                    "price_f", Float.toString(myRandom.nextInt(10000)));
            for(int variantId = 0; variantId< (myRandom.nextInt(maxChildren) + 1); variantId++) {
                SolrInputDocument childdoc = sdoc(IDField, uniqueID + "_" + variantId,
                        "v_brand_s", random(brand),
                        "v_color_s", random(color),
                        "parent_b", "false",
                        "v_price_f", Float.toString(myRandom.nextInt(100)));
                for(String fieldName:sdoc.keySet()) {
                    if(!fieldName.equals(IDField) && !fieldName.equals("parent_b"))
                        childdoc.addField(fieldName, sdoc.getFieldValue(fieldName));
                }
                //Adding anonymous childDocuments
                sdoc.addChildDocument(childdoc);
            }
            solrInputDocuments.add(sdoc);
        }
        String jsonUpdate = jsonAdd(solrInputDocuments.toArray(new SolrInputDocument[0]));
        createIndex(jsonUpdate);
    }

    private static String random(String[] array) {
        return array[myRandom.nextInt(array.length)];
    }

    /**
     * Util method to form assertion for given numFound
     */
    public static String toAssertion(int numFound) {
        return "//*[@numFound='" + numFound + "']";
    }

    /**
     *
     * Returns SolrDocumentList from response, Works only for javabin
     */
    private SolrDocumentList getSolrDocumentList(SolrQueryRequest solrQueryRequest, SolrQueryResponse queryResponse)
            throws IOException {
        final NamedList<Object> unmarshalled = respToNamedList(solrQueryRequest, queryResponse);
        return (SolrDocumentList)(unmarshalled.get("response"));
    }

    /**
     * Indexes a given document in json format
     */
    public static void createIndex(String jsonUpdate) throws Exception {
        assertU(delQ("*:*"));
        assertU(commit());
        updateJ(jsonUpdate, null);
        assertU(commit());
    }

    /**
     * Converts given {@link SolrQueryResponse} to {@link NamedList}
     * <p>
     *     This works only for {@link BinaryQueryResponseWriter}.
     *     This closes the the given {@param req} and clears request info
     * </p>
     * Returns SolrDocumentList from response, Works only for javabin
     */
    @SuppressWarnings("unchecked")
    public static NamedList<Object> respToNamedList(SolrQueryRequest req, SolrQueryResponse resp)
            throws IOException {
        BinaryQueryResponseWriter responseWriter = (BinaryQueryResponseWriter) req.getCore()
                .getQueryResponseWriter(req);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        responseWriter.write(bytes, req, resp);

        final NamedList<Object> unmarshalled;
        try (JavaBinCodec jbc = new JavaBinCodec()) {
            unmarshalled = (NamedList<Object>) jbc.unmarshal(
                    new ByteArrayInputStream(bytes.toByteArray()));
        }
        req.close();
        return unmarshalled;
    }
}
