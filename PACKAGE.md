## Install as a package

* Start Solr (version 8.4 or later) nodes with -Denable.packages=true
```bash
$ bin/solr -c -Denable.packages=true
```

* Add repository:
```bash
$ bin/solr package add-repo child-transformer "https://raw.githubusercontent.com/munendrasn/child-subquery/master/repo/"
```

* List available packages:
```bash
 $ bin/solr package list-available
```

* Install the package
```bash
$ bin/solr package install child-transformer
```

* Deploy package on the collection

```bash
$ bin/solr package deploy child-transformer -y -collections test -p TRANSFORMER_NAME=childquery
```
> `TRANSFORMER_NAME` when not specified, defaults to `childquery`

Refer [this](README.md#childsubquerytransformer) for sample usage

## Development [Upcoming]

> Refer to [Ishan](https://github.com/chatman)'s [repo](https://github.com/chatman/question-answering#development)
