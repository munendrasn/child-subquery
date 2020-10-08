package com.munendrasn.transformer;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.response.transform.TransformerFactory;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SortSpec;
import org.apache.solr.search.SyntaxError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This transformer executes child query per every result document. It must be given an unique name.
 * There might be a few of them, eg <code>fl=*,foo:[childquery],bar:[childquery]</code>.
 * Every [childquery] occurrence adds a field into a result document with the given name,
 * the value of this field is a document list, which is a result of executing subquery using
 * document fields as an input.
 *
 * The "parentFilter" parameter is mandatory parameter for each childquery
 * This transform is combination of {@link org.apache.solr.response.transform.SubQueryAugmenterFactory}
 *  and {@link org.apache.solr.response.transform.ChildDocTransformerFactory}
 * <p>
 *     Note: This should be used only with {@link org.apache.solr.search.join.BlockJoinParentQParser}
 *     In other cases, this may not work as expected(this wouldn't throw any error)
 * </p>
 */
public class ChildSubQueryAugmenterFactory extends TransformerFactory {

    @Override
    public DocTransformer create(String field, SolrParams params, SolrQueryRequest req) {
        SchemaField uniqueKeyField = req.getSchema().getUniqueKeyField();
        if (uniqueKeyField == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    " ChildSubQueryDocTransformer requires the schema to have a uniqueKeyField.");
        }

        if (field.contains("[") || field.contains("]")) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "please give an explicit name for [childquery] column ie fl=relation:[childquery ..]");
        }
        checkThereIsNoDupe(field, req.getContext());
        SolrParams subParams = retainAndShiftPrefix(req.getParams(), field + ".");

        // check if parentFilter is sent or not
        String parentFilter = subParams.get( "parentFilter" );
        if( parentFilter == null ) {
            throw new SolrException( SolrException.ErrorCode.BAD_REQUEST,
                    "parentFilter is missing for childquery '" + field + "'");
        }
        BitSetProducer parentBitSet = null;
        try {
            Query parentFilterQuery = QParser.getParser( parentFilter, req).getQuery();
            parentBitSet = new QueryBitSetProducer(parentFilterQuery);
        } catch (SyntaxError syntaxError) {
            throw new SolrException( SolrException.ErrorCode.BAD_REQUEST,
                    "Failed to create correct parent filter query for '" + field + "'");
        }
        SolrQueryRequest subQueryRequest = new LocalSolrQueryRequest(req.getCore(), subParams);

        // parse Query from subquery params
        try {
            String defType = subParams.get(QueryParsing.DEFTYPE, QParserPlugin.DEFAULT_QTYPE);
            String queryString = subParams.get(CommonParams.Q);
            QParser parser = QParser.getParser(queryString, defType, subQueryRequest);
            Query q = parser.getQuery();
            if (q == null) {
                // normalize a null query to a query that matches all children
                q = new MatchAllDocsQuery();
            }
            SortSpec sortSpec = parser.getSortSpec(true);

            // parse filter queries from subquery params
            String[] fqs = subQueryRequest.getParams().getParams(CommonParams.FQ);
            List<Query> filters = new ArrayList<>();
            if (fqs != null && fqs.length != 0) {
                for (String fq : fqs) {
                    if (fq != null && fq.trim().length() != 0) {
                        QParser fqp = QParser.getParser(fq, req);
                        fqp.setIsFilter(true);
                        filters.add(fqp.getQuery());
                    }
                }
            }
            return new ChildSubQueryDocTransformer(field, uniqueKeyField, q, filters, sortSpec,
                    subQueryRequest, parentBitSet);
        } catch (SyntaxError e) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
        }
    }

    private void checkThereIsNoDupe(String field, Map<Object, Object> context) {
        // find a map
        final Map conflictMap;
        final String conflictMapKey = getClass().getSimpleName();
        if (context.containsKey(conflictMapKey)) {
            conflictMap = (Map) context.get(conflictMapKey);
        } else {
            conflictMap = new HashMap<>();
            context.put(conflictMapKey, conflictMap);
        }
        // check entry absence
        if (conflictMap.containsKey(field)) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                    "[childquery] name " + field + " is duplicated");
        } else {
            conflictMap.put(field, true);
        }
    }

    private SolrParams retainAndShiftPrefix(SolrParams params, String subPrefix) {
        ModifiableSolrParams out = new ModifiableSolrParams();
        Iterator<String> baseKeyIt = params.getParameterNamesIterator();
        while (baseKeyIt.hasNext()) {
            String key = baseKeyIt.next();

            if (key.startsWith(subPrefix)) {
                out.set(key.substring(subPrefix.length()), params.getParams(key));
            }
        }
        return out;
    }

}
