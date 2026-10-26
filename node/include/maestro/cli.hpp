#pragma once

#include <ostream>
#include <span>
#include <string>

#include "maestro/net/server.hpp"

namespace maestro::cli {

struct Options {
  std::string OtlpEndpoint = "localhost:4317"; // OTLP/gRPC
  net::ServerOptions Server;
};

enum class ArgsResult { Parsed, Help, Error };

/// One search thread per core, and at least one.
unsigned defaultWorkers();

void printUsage(std::ostream &OS);

/// Fills `Opts` from the command line (`Args` includes argv[0]). Stops at the
/// first `--help` or invalid argument.
ArgsResult parseArgs(std::span<char *const> Args, Options &Opts);

} // namespace maestro::cli
