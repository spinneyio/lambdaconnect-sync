# lambdaconnect-sync

Synchronisation library. <br> <br>
[![Build Status](https://app.travis-ci.com/spinneyio/lambdaconnect-sync.svg?branch=master)](https://app.travis-ci.com/spinneyio/lambdaconnect-sync)

## Installation

Leiningen coordinates:
```clojure
[io.spinney/lambdaconnect-sync "1.0.16"]
```

## Usage

Each function requires a config as the first argument:

```
(def config {:log (constantly nil)
             :as-of d/as-of
             :pull-many d/pull-many
             :pull d/pull
             :q d/q
             :history d/history
             :tx->t d/tx->t
             :with d/with
             :basis-t d/basis-t})
```

## License

Copyright © 2022 Spinney

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
