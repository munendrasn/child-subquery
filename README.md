# child-subquery

Solr contains [child](https://lucene.apache.org/solr/guide/8_6/transforming-result-documents.html#child-childdoctransformerfactory) and [subquery](https://lucene.apache.org/solr/guide/8_6/transforming-result-documents.html#subquery) document transformers which can be used to fetch child documents of given Parent document. So, let's address the need for another doc transformer for fetching child docs. 

> NOTE: Even though, From 8x Solr supports both labelled and unlabelled/anonymous child documents. HenceForth, any mentions of child docs refers to anonymous child document

### Indexing Nested documents

> The sample documents are picked from http://yonik.com/solr-nested-objects/

Assume that collection `test` already exists and let's add one nested document. This documents contains information about a book along with 2 reviews.

> Refer [this](https://lucene.apache.org/solr/guide/8_6/solr-tutorial.html) for quickly setting up Solr

```bash
$ curl http://localhost:8983/solr/test/update?commitWithin=3000 -d '
[
 {id : book1, type_s:book, title_t : "The Way of Kings", author_s : "Brandon Sanderson",
  cat_s:fantasy, pubyear_i:2010, publisher_s:Tor,
  _childDocuments_ : [
    { id: book1_c1, type_s:review, review_dt:"2015-01-03T14:30:00Z",
      stars_i:3, author_s:yonik,
      comment_t:"A great start to what looks like an epic series!"
    }
    ,
    { id: book1_c2, type_s:review, review_dt:"2014-03-15T12:00:00Z",
      stars_i:4, author_s:dan,
      comment_t:"This book was too long."
    }
  ]
 }
]'
```

### ChildDocTransformer

Once the documents are [indexed as nested block](https://lucene.apache.org/solr/guide/8_6/indexing-nested-documents.html). `[child]` doc transformer can be used at query-time. This transformer makes use of index-block structure of retrieval
```bash
$ curl http://localhost:8983/solr/test/select?q=cat_s:fantasy&fl=id,[child parentFilter=type_s:book fl=id]
```

The docs returned in the response. The child docs are returned in the they are indexed
```json
{
  "id": "book1",
  "_childDocuments_": [
    {
      "id": "book1_c1"
    },
    {
      "id": "book1_c2"
    }
  ]
}
```

Suppose, we want to sort child docs(reviews) by rating(`stars_i`) such that high rated ones returned at top of the block or add extra weightage(boost) to reviews by certain people. These requirements can't be supported using child doc transformer as it doesn't take any query or sort as inputs

> Especially In E-commerce, boosting, sorting, and querying child docs more common when variants of Products are indexed as child docs

### SubQueryTransformer

This is a generic transformer which executes a separate query per document. The above query could be modified to use subquery transformer with sort parameter if required.

```bash
$ curl http://localhost:8983/solr/test/select?q=cat_s:fantasy&fl=id,_child_:[subquery]&_child_.q={!edismax qf=_root_ v=$rows.id}&_child_.fq=-type_s:book&_child_.sort=stars_i desc
```

The docs returned in the response. The child docs are returned in the specified sort order. Using this, child documents could be boosted, queried and sorted.
```json
{
  "id": "book1",
  "_child_": [
    {
      "id": "book1_c2"
    },
    {
      "id": "book1_c1"
    }
  ]
}
```

* Subquery transformer is too generic for the use-case and doesn't use the nested-block information
* Either need additional field in child docs which contains parent `id` information or need to send additional negative filter to exclude parent product (`{!edismax qf=_root_ v=$rows.id}` matches parent doc too).

### ChildSubQueryTransformer

To overcome all the above issues, A new custom document transformer was added which internally uses `ParentChildrenBlockJoinQuery`.
This doc transformer essentially combination of above two transformers

```bash
$ curl http://localhost:8983/solr/test/select?q=cat_s:fantasy&fl=id,_child_:[childquery]&_child_.parentFilter=type_s:book&_child_.q=*:*&_child_.fq=-type_s:book&_child_.sort=stars_i desc
```

* `parentFilter` - Similar to `[child]`, filter identifying the parent documents. This is required parameter.

All other parameters are optional. When child query is not specified then, behavior is similar to `[child]` but request and response format is similar to `[subquery]`

```json
{
  "id": "book1",
  "_child_": [
    {
      "id": "book1_c2"
    },
    {
      "id": "book1_c1"
    }
  ]
}
```

### Project Setup

* This requires java `1.8`. Refer [this](https://adoptopenjdk.net/installation.html?variant=openjdk8&jvmVariant=hotspot#x64_linux-jdk) for installation help
* Install maven
For example in Debian
```bash
$ sudo apt-get install maven
```

### Usage

Build the binary
```bash
$ maven clean package
```

Once the binary is generated, it can be added as Solr-plugin. Solr supports 3 different ways to add [Plugins](https://lucene.apache.org/solr/guide/8_4/solr-plugins.html#installing-plugins).

Once plugin is installed, add this to `solrconfig.xml`
```xml
  <transformer name="childquery" class="org.munendrasn.transformer.ChildSubQueryAugmenterFactory" />
```

Or Add it using [Config API](https://lucene.apache.org/solr/guide/8_6/config-api.html#advanced-commands-for-components)
