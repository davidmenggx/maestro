#include <charconv>
#include <csignal>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <iostream>
#include <ostream>
#include <span>
#include <string>
#include <string_view>
#include <thread>

#include <asio/io_context.hpp>
#include <asio/signal_set.hpp>

#include "maestro/net/server.hpp"
#include "maestro/obs.hpp"

namespace {

struct Options {
  std::string OtlpEndpoint = "localhost:4317"; // OTLP/gRPC
  maestro::net::ServerOptions Server;
};

enum class ArgsResult { Parsed, Help, Error };

unsigned defaultWorkers() {
  const unsigned Cores = std::thread::hardware_concurrency();
  return Cores == 0 ? 1 : Cores;
}

void printUsage(std::ostream &OS) {
  const Options Defaults;
  OS << "usage: maestro-node [options]\n"
     << "  --bind <address>       address to listen on (default "
     << Defaults.Server.Bind << ")\n"
     << "  --world-port <port>    TCP port of the world stream (default "
     << Defaults.Server.WorldPort << ")\n"
     << "  --control-port <port>  TCP port of the control stream (default "
     << Defaults.Server.ControlPort << ")\n"
     << "  --request-port <port>  UDP port of the request stream (default "
     << Defaults.Server.RequestPort << ")\n"
     << "  --workers <n>          search threads (default " << defaultWorkers()
     << ", one per core)\n"
     << "  --otlp <host:port>     OTLP/gRPC collector endpoint (default "
     << Defaults.OtlpEndpoint << ")\n"
     << "  --help                 show this message\n";
}

/// Parses all of `Text` as a number in `[Min, Max]`.
template <typename T>
bool parseNumber(std::string_view Text, T Min, T Max, T &Out) {
  T Value{};
  const auto [End, Error] =
      std::from_chars(Text.data(), Text.data() + Text.size(), Value);
  if (Error != std::errc() || End != Text.data() + Text.size() || Value < Min ||
      Value > Max)
    return false;
  Out = Value;
  return true;
}

// Fills `Opts` from the command line (`Args` includes argv[0]).
ArgsResult parseArgs(std::span<char *const> Args, Options &Opts) {
  Opts.Server.Workers = defaultWorkers();
  for (std::size_t I = 1; I < Args.size(); ++I) {
    const std::string_view Arg = Args[I];

    // Consumes and returns the flag's value, or nullptr if it is missing.
    auto takeValue = [&]() -> const char * {
      return I + 1 < Args.size() ? Args[++I] : nullptr;
    };
    auto takePort = [&](std::uint16_t &Port) {
      const char *Value = takeValue();
      return Value && parseNumber<std::uint16_t>(Value, 1, 65535, Port);
    };

    if (Arg == "--help")
      return ArgsResult::Help;

    bool Valid = true;
    if (Arg == "--otlp") {
      const char *Value = takeValue();
      Valid = Value != nullptr;
      if (Valid)
        Opts.OtlpEndpoint = Value;
    } else if (Arg == "--bind") {
      const char *Value = takeValue();
      Valid = Value != nullptr;
      if (Valid)
        Opts.Server.Bind = Value;
    } else if (Arg == "--world-port") {
      Valid = takePort(Opts.Server.WorldPort);
    } else if (Arg == "--control-port") {
      Valid = takePort(Opts.Server.ControlPort);
    } else if (Arg == "--request-port") {
      Valid = takePort(Opts.Server.RequestPort);
    } else if (Arg == "--workers") {
      const char *Value = takeValue();
      Valid =
          Value && parseNumber<unsigned>(Value, 1, 1024, Opts.Server.Workers);
    } else {
      Valid = false;
    }
    if (!Valid)
      return ArgsResult::Error;
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
  int Status = 0;
  try {
    asio::io_context Io;
    maestro::net::Server Node(Io, Opts.Server);
    asio::signal_set Signals(Io, SIGINT, SIGTERM);
    Signals.async_wait([&](const asio::error_code &, int) { Node.stop(); });
    std::clog << "maestro-node: listening on " << Opts.Server.Bind << ", world "
              << Node.worldPort() << ", control " << Node.controlPort()
              << ", requests " << Node.requestPort() << ", "
              << Opts.Server.Workers << " workers\n";
    Io.run();
  } catch (const std::exception &Error) {
    std::cerr << "maestro-node: " << Error.what() << '\n';
    Status = 1;
  }
  maestro::obs::shutdown();
  return Status;
}
