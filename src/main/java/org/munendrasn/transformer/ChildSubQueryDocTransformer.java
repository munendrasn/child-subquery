package org.munendrasn.transformer;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ParentChildrenBlockJoinQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.BasicResultContext;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocList;
import org.apache.solr.search.ReturnFields;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrReturnFields;
import org.apache.solr.search.SortSpec;

import java.io.IOException;
import java.util.List;

class ChildSubQueryDocTransformer extends DocTransformer {

    private final String name;
    private final SchemaField idField;
    private Query childQuery;
    private List<Query> filters;
    private SortSpec sortSpec;
    private SolrQueryRequest solrQueryRequest;
    private BitSetProducer parentBitSet;

    public ChildSubQueryDocTransformer(
            String name,
            SchemaField idField,
            Query childQuery,
            List<Query> filters,
            SortSpec sortSpec,
            SolrQueryRequest solrQueryRequest,
            BitSetProducer parentBitSet
    ) {
        this.name = name;
        this.idField = idField;
        this.childQuery = childQuery;
        this.filters = filters;
        this.sortSpec = sortSpec;
        this.solrQueryRequest = solrQueryRequest;
        this.parentBitSet = parentBitSet;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     *
     * This transformer would be used with real-time get and normal search.
     * Using this in conjunction with real-time get would need IndexSearcher access
     */
    public boolean needsSolrIndexSearcher() {
        return true;
    }

    @Override
    public void transform(SolrDocument doc, int docid) throws IOException {
        // block join query
        ParentChildrenBlockJoinQuery query = new ParentChildrenBlockJoinQuery(parentBitSet, childQuery, docid);
        // get children
        // TODO: what should be the flags here??
        DocList children = context.getSearcher()
                .getDocList(
                        query, filters, sortSpec.getSort(), sortSpec.getOffset(), sortSpec.getCount(),
                        SolrIndexSearcher.GET_SCORES
                );
        ReturnFields returnFields = new SolrReturnFields(solrQueryRequest);
        ResultContext resultContext = new BasicResultContext(
                children, returnFields, context.getSearcher(), query, solrQueryRequest);
        doc.setField(getName(), resultContext);
    }
}
