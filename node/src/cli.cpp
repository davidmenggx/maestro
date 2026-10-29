#include "maestro/cli.hpp"

#include <charconv>
#include <cstddef>
#include <cstdint>
#include <string_view>
#include <thread>

namespace maestro::cli {

namespace {

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

} // namespace

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
     << "  --help                 show this message\n";
}

ArgsResult parseArgs(std::span<char *const> Args, Options &Opts) {
  Opts.Server.Node.Workers = defaultWorkers();
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
    if (Arg == "--bind") {
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
      Valid = Value &&
              parseNumber<unsigned>(Value, 1, 1024, Opts.Server.Node.Workers);
    } else {
      Valid = false;
    }
    if (!Valid)
      return ArgsResult::Error;
  }
  return ArgsResult::Parsed;
}

} // namespace maestro::cli
