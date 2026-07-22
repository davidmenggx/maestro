#include <charconv>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <ostream>
#include <span>
#include <string>
#include <string_view>

#include <asio.hpp>

#include "maestro/obs.hpp"

namespace {

struct Options {
  std::uint16_t Port = 47800;
  std::string OtlpEndpoint = "localhost:4317"; // OTLP/gRPC
  bool Stateless = false;
};

enum class ArgsResult { Parsed, Help, Error };

void printUsage(std::ostream &OS) {
  OS << "usage: maestro-node [options]\n"
     << "  --port <n>          TCP port to listen on (default 47800)\n"
     << "  --otlp <host:port>  OTLP/gRPC collector endpoint\n"
     << "  --stateless         run without persistent world state\n"
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

    if (Arg == "--stateless") {
      Opts.Stateless = true;
    } else if (Arg == "--otlp") {
      const char *Value = takeValue();
      if (!Value)
        return ArgsResult::Error;
      Opts.OtlpEndpoint = Value;
    } else if (Arg == "--port") {
      const char *Value = takeValue();
      if (!Value)
        return ArgsResult::Error;
      const std::string_view Text = Value;
      unsigned Port = 0;
      const auto [Ptr, Ec] =
          std::from_chars(Text.data(), Text.data() + Text.size(), Port);
      if (Ec != std::errc{} || Ptr != Text.data() + Text.size() ||
          Port > 0xFFFF)
        return ArgsResult::Error;
      Opts.Port = static_cast<std::uint16_t>(Port);
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

  asio::io_context Io;
  asio::ip::tcp::acceptor Acceptor(
      Io, asio::ip::tcp::endpoint(asio::ip::tcp::v4(), Opts.Port));

  std::cout << "maestro-node bound to :" << Opts.Port
            << (Opts.Stateless ? "  [stateless]" : "  [stateful]") << '\n';

  Acceptor.close();
  maestro::obs::shutdown();
  return 0;
}
