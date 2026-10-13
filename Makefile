# Maestro top-level build. Orchestrates:
#   * flatc          - the FlatBuffers compiler (built once from a pinned tag)
#   * schema         - generate C++ and Java bindings from schema/*.fbs
#   * node           - the C++20 compute node (CMake + FetchContent)
#   * server         - the Java Fabric server mod (Gradle + Loom)
#
# Common targets:
#   make            build node + server
#   make node       build the C++ compute node
#   make server     build the Fabric mod jar
#   make test       run both unit test suites
#   make fuzz       run the controller's deterministic simulation seed sweeps
#                   (TIER=shallow|deep|coverage narrows it to one tier)
#   make test-boot  boot the released mod and assert the mixins applied
#   make capture    record vanilla parity fixtures (SEED="1 2" N=cases)
#   make clean      remove build outputs (keeps fetched deps)
#   make help       list targets

SHELL := /bin/bash
ROOT  := $(abspath .)
BUILD := $(ROOT)/build

SCHEMA_DIR := $(ROOT)/schema
FBS        := $(wildcard $(SCHEMA_DIR)/*.fbs)

GEN_CPP  := $(ROOT)/node/generated
GEN_JAVA := $(ROOT)/server/controller/src/generated/java

FLATC := $(BUILD)/tools/bin/flatc

# Minecraft 26.1 needs JDK 25. The `java.toolchain` blocks in the Gradle builds
# select it, and Gradle finds installed JDKs on its own.
GRADLE := ./gradlew

# Toggle the OpenTelemetry C++ SDK (heavy) for the node build.
MAESTRO_WITH_OTEL ?= ON

# Sanitizers for the node build, e.g. `address,undefined` or `thread`. Empty
# builds without any.
MAESTRO_SANITIZE ?=

.PHONY: all help flatc schema schema-cpp schema-java \
        node node-configure node-test \
        server server-test fuzz test test-boot capture run-node run-server clean distclean

all: node server ## Build the C++ node and the Java mod

help: ## List available targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n",$$1,$$2}'

# ---------------------------------------------------------------------------
# FlatBuffers compiler
# ---------------------------------------------------------------------------
$(FLATC):
	cmake -S tools -B $(BUILD)/tools -DCMAKE_BUILD_TYPE=Release
	cmake --build $(BUILD)/tools --target flatc -j

flatc: $(FLATC) ## Build the FlatBuffers compiler (flatc)

# ---------------------------------------------------------------------------
# Schema code generation
# ---------------------------------------------------------------------------
schema: schema-cpp schema-java ## Generate C++ and Java FlatBuffers bindings

schema-cpp: $(FLATC) ## Generate C++ bindings into node/generated
	@rm -rf $(GEN_CPP) && mkdir -p $(GEN_CPP)
	$(FLATC) --cpp --scoped-enums --gen-name-strings -o $(GEN_CPP) $(FBS)

schema-java: $(FLATC) ## Generate Java bindings into server/controller/src/generated/java
	@rm -rf $(GEN_JAVA) && mkdir -p $(GEN_JAVA)
	$(FLATC) --java -o $(GEN_JAVA) $(FBS)

# ---------------------------------------------------------------------------
# C++ compute node
# ---------------------------------------------------------------------------
node-configure: schema-cpp
	cmake -S node -B $(BUILD)/node -DCMAKE_BUILD_TYPE=RelWithDebInfo \
		-DMAESTRO_WITH_OTEL=$(MAESTRO_WITH_OTEL) \
		-DMAESTRO_SANITIZE=$(MAESTRO_SANITIZE)

node: node-configure ## Configure and build the C++ compute node
	cmake --build $(BUILD)/node -j

node-test: node ## Run the C++ unit tests
	ctest --test-dir $(BUILD)/node --output-on-failure

run-node: node ## Print the node binary's usage
	$(BUILD)/node/maestro-node --help

# ---------------------------------------------------------------------------
# Java Fabric server mod
# ---------------------------------------------------------------------------
server: schema-java ## Build the Fabric server mod jar
	cd server && $(GRADLE) build

server-test: schema-java ## Run the Java unit tests (mod and controller), fuzz excluded
	cd server && $(GRADLE) test :controller:test

fuzz: schema-java ## Run the DST seed sweeps (TIER=shallow|deep|coverage SEED=n RUNS=n)
	cd server && $(GRADLE) :controller:$(if $(TIER),fuzz-$(TIER),fuzz) \
		$(if $(SEED),-Dmaestro.dst.seed=$(SEED)) \
		$(if $(RUNS),-Dmaestro.dst.runs=$(RUNS))

run-server: schema-java ## Boot a dev server (WORLD=<name> loads worlds/<name>)
	cd server && $(GRADLE) runServer $(if $(WORLD),-Pworld=$(WORLD)) $(if $(KEEP_WORLD),-PkeepWorld)

test-boot: schema-java ## Boot the released mod and assert the mixins applied
	tools/boot-smoke-test.sh

capture: schema-java ## Record parity fixtures into fixtures/parity (SEED="1 2" N=cases)
	tools/capture-fixtures.sh "$(if $(SEED),$(SEED),$(error set SEED, e.g. SEED="1 2 3"))" \
		$(if $(N),$(N),100)

# ---------------------------------------------------------------------------
# Aggregate / cleanup
# ---------------------------------------------------------------------------
test: node-test server-test ## Run every unit test suite (see `fuzz` for the sweeps)

clean: ## Remove build outputs (keeps fetched dependencies)
	rm -rf $(BUILD)/node $(GEN_CPP) $(GEN_JAVA)
	cd server && $(GRADLE) clean || true

distclean: ## Remove everything, including fetched dependencies
	rm -rf $(BUILD) $(GEN_CPP) $(GEN_JAVA) server/.gradle server/build
