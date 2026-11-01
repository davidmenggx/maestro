SHELL := /bin/bash
ROOT  := $(abspath .)
BUILD := $(ROOT)/build

SCHEMA_DIR := $(ROOT)/schema
FBS        := $(wildcard $(SCHEMA_DIR)/*.fbs)

GEN_CPP  := $(ROOT)/node/generated
GEN_JAVA := $(ROOT)/server/controller/src/generated/java

FLATC := $(BUILD)/tools/bin/flatc

# Minecraft 26.1 needs JDK 25, selected by the Gradle `java.toolchain` blocks.
GRADLE := ./gradlew

# e.g. `address,undefined` or `thread`.
MAESTRO_SANITIZE ?=

.PHONY: all help flatc schema schema-cpp schema-java \
        node node-configure node-test \
        server server-test fuzz test test-boot test-game test-e2e capture conformance run-node run-server \
        obs-up obs-down coverage clean distclean

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
	cd server && $(GRADLE) test mixinTest :controller:test

fuzz: schema-java ## Run the DST seed sweeps (TIER=shallow|deep|coverage SEED=n RUNS=n)
	cd server && $(GRADLE) :controller:$(if $(TIER),fuzz-$(TIER),fuzz) \
		$(if $(SEED),-Dmaestro.dst.seed=$(SEED)) \
		$(if $(RUNS),-Dmaestro.dst.runs=$(RUNS))

run-server: schema-java ## Boot a dev server (WORLD=<name> loads worlds/<name>)
	cd server && $(GRADLE) runServer $(if $(WORLD),-Pworld=$(WORLD)) $(if $(KEEP_WORLD),-PkeepWorld)

test-boot: schema-java ## Boot the released mod and assert the mixins applied
	tools/boot-smoke-test.sh

test-game: schema-java ## Run the game tests, which need a running server, headless
	cd server && $(GRADLE) runGameTest --console=plain

# Each run is `offload-submode[-faults]`.
E2E_RUNS := $(if $(OFFLOAD),$(OFFLOAD)-$(or $(SUBMODE),batch)-$(FAULTS),shadow-batch shadow-stream on-batch on-batch-failover on-stream on-stream-failover on-batch-split on-batch-replica on-stream-split on-stream-replica shadow-batch-blocks shadow-batch-net shadow-batch-topology)

test-e2e: node schema-java ## Run the released mod against real nodes (OFFLOAD=shadow|on SUBMODE=batch|stream FAULTS=failover|split|replica|blocks|net|topology pick one run; all by default)
	$(foreach run,$(E2E_RUNS),OFFLOAD=$(word 1,$(subst -, ,$(run))) SUBMODE=$(word 2,$(subst -, ,$(run))) FAULTS=$(word 3,$(subst -, ,$(run))) tools/e2e-test.sh &&) true

capture: schema-java ## Record parity fixtures into fixtures/parity (SEED="1 2" N=cases)
	tools/capture-fixtures.sh "$(if $(SEED),$(SEED),$(error set SEED, e.g. SEED="1 2 3"))" \
		$(if $(N),$(N),100)

conformance: schema-java ## Write the node conformance fixture into fixtures/conformance
	cd server && $(GRADLE) :controller:test --tests '*ConformanceTest*' \
		-Dmaestro.conformance.write=true

# ---------------------------------------------------------------------------
# Local telemetry
# ---------------------------------------------------------------------------
# One container holding an OpenTelemetry collector, Prometheus, Tempo, Loki and Grafana. The mod's
# default `otlpEndpoint` points at it. Its data, dashboards included, lives in the volume.
OBS_IMAGE     := docker.io/grafana/otel-lgtm:0.36.0
OBS_CONTAINER := maestro-obs

# A container left stopped, as after a reboot, is started again rather than made anew.
obs-up: ## Start the local telemetry stack (Grafana on localhost:3000, admin/admin)
	docker start $(OBS_CONTAINER) 2>/dev/null || docker run -d --name $(OBS_CONTAINER) --init \
		-p 127.0.0.1:3000:3000 -p 127.0.0.1:4317:4317 \
		-v $(OBS_CONTAINER):/data -e GF_PATHS_DATA=/data/grafana \
		$(OBS_IMAGE)

obs-down: ## Stop the local telemetry stack, keeping its data
	docker rm -f $(OBS_CONTAINER)

# ---------------------------------------------------------------------------
# Coverage
# ---------------------------------------------------------------------------
# Clang's source-based coverage. llvm-profdata and llvm-cov must match the compiler, so they are
# taken from its directory, looked up only when a recipe uses it.
COVERAGE_CXX  ?= clang++
LLVM_BIN       = $(dir $(realpath $(shell command -v $(COVERAGE_CXX))))
COVERAGE      := $(BUILD)/coverage
NODE_COVERAGE := $(BUILD)/node-coverage

coverage: schema ## Measure the unit suites' line and branch coverage into build/coverage (COVERAGE_CXX=clang++)
	cd server && $(GRADLE) --init-script $(ROOT)/tools/jacoco.init.gradle \
		-Dmaestro.coverage.dir=$(COVERAGE)/java coverageReport
	cmake -S node -B $(NODE_COVERAGE) -DCMAKE_BUILD_TYPE=Debug \
		-DCMAKE_CXX_COMPILER=$(COVERAGE_CXX) \
		-DCMAKE_CXX_FLAGS="-fprofile-instr-generate -fcoverage-mapping"
	cmake --build $(NODE_COVERAGE) -j
	rm -rf $(NODE_COVERAGE)/profiles
	LLVM_PROFILE_FILE=$(NODE_COVERAGE)/profiles/%p.profraw \
		ctest --test-dir $(NODE_COVERAGE) -j $(shell nproc) --output-on-failure
	$(LLVM_BIN)llvm-profdata merge -sparse $(NODE_COVERAGE)/profiles/*.profraw \
		-o $(NODE_COVERAGE)/tests.profdata
	$(LLVM_BIN)llvm-cov show $(NODE_COVERAGE)/maestro_tests -object $(NODE_COVERAGE)/maestro-node \
		-instr-profile=$(NODE_COVERAGE)/tests.profdata -show-branches=count \
		-format=html -output-dir=$(COVERAGE)/node node/src node/include
	$(LLVM_BIN)llvm-cov report $(NODE_COVERAGE)/maestro_tests -object $(NODE_COVERAGE)/maestro-node \
		-instr-profile=$(NODE_COVERAGE)/tests.profdata node/src node/include

# ---------------------------------------------------------------------------
# Aggregate / cleanup
# ---------------------------------------------------------------------------
test: node-test server-test ## Run every unit test suite (see `fuzz` for the sweeps)

clean: ## Remove build outputs (keeps fetched dependencies)
	rm -rf $(BUILD)/node $(NODE_COVERAGE) $(COVERAGE) $(GEN_CPP) $(GEN_JAVA)
	cd server && $(GRADLE) clean || true

distclean: ## Remove everything, including fetched dependencies
	rm -rf $(BUILD) $(GEN_CPP) $(GEN_JAVA) server/.gradle server/build
