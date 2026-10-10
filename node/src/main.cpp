#include <cstddef>
#include <iostream>
#include <ostream>
#include <span>
#include <string>
#include <string_view>

#include "maestro/obs.hpp"

namespace {

struct Options {
  std::string OtlpEndpoint = "localhost:4317"; // OTLP/gRPC
};

enum class ArgsResult { Parsed, Help, Error };

void printUsage(std::ostream &OS) {
  const Options Defaults;
  OS << "usage: maestro-node [options]\n"
     << "  --otlp <host:port>  OTLP/gRPC collector endpoint (default "
     << Defaults.OtlpEndpoint << ")\n"
     << "  --help              show this message\n";
}

// Fills `Opts` from the command line (`Args` includes argv[0]).
ArgsResult parseArgs(std::span<char *const> Args, Options &Opts) {
  for (std::size_t I = 1; I < Args.size(); ++I) {
    const std::string_view Arg = Args[I];

    // Consumes and returns the flag's value, or nullptr if it is missing.
    auto takeValue = [&]() -> const char * {
      return I + 1 < Args.size() ? Args[++I] : nullptr;
    };

    if (Arg == "--help")
      return ArgsResult::Help;

    if (Arg == "--otlp") {
      const char *Value = takeValue();
      if (!Value)
        return ArgsResult::Error;
      Opts.OtlpEndpoint = Value;
    } else {
      return ArgsResult::Error;
    }
  }
  return ArgsResult::Parsed;
}

} // namespace

int main(int Argc, char **Argv) {
  Options Opts;
  switch (parseArgs(
      std::span<char *const>(Argv, static_cast<std::size_t>(Argc)), Opts)) {
  case ArgsResult::Help:
    printUsage(std::cout);
    return 0;
  case ArgsResult::Error:
    printUsage(std::cerr);
    return 2;
  case ArgsResult::Parsed:
    break;
  }

  maestro::obs::init("maestro-node", Opts.OtlpEndpoint);
  maestro::obs::shutdown();
  return 0;
}
