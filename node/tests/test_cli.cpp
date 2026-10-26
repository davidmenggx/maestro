#include "maestro/cli.hpp"

#include <initializer_list>
#include <sstream>
#include <string>
#include <vector>

#include <gtest/gtest.h>

using namespace maestro::cli;

namespace {

/// Parses `Args` as the arguments after the program name.
ArgsResult parse(std::initializer_list<std::string> Args, Options &Opts) {
  std::vector<std::string> Words{"maestro-node"};
  Words.insert(Words.end(), Args);
  std::vector<char *> Argv;
  for (std::string &Word : Words)
    Argv.push_back(Word.data());
  return parseArgs(Argv, Opts);
}

ArgsResult parse(std::initializer_list<std::string> Args) {
  Options Opts;
  return parse(Args, Opts);
}

} // namespace

TEST(Cli, NoArgumentsKeepTheDefaults) {
  Options Opts;
  ASSERT_EQ(parse({}, Opts), ArgsResult::Parsed);
  const Options Defaults;
  EXPECT_EQ(Opts.OtlpEndpoint, Defaults.OtlpEndpoint);
  EXPECT_EQ(Opts.Server.Bind, Defaults.Server.Bind);
  EXPECT_EQ(Opts.Server.WorldPort, Defaults.Server.WorldPort);
  EXPECT_EQ(Opts.Server.ControlPort, Defaults.Server.ControlPort);
  EXPECT_EQ(Opts.Server.RequestPort, Defaults.Server.RequestPort);
  EXPECT_EQ(Opts.Server.Node.Workers, defaultWorkers());
  EXPECT_GE(defaultWorkers(), 1u);
}

TEST(Cli, EveryFlagSetsItsOption) {
  Options Opts;
  ASSERT_EQ(parse({"--bind", "127.0.0.1", "--world-port", "1", "--control-port",
                   "65535", "--request-port", "30000", "--workers", "1024",
                   "--otlp", "collector:4317"},
                  Opts),
            ArgsResult::Parsed);
  EXPECT_EQ(Opts.Server.Bind, "127.0.0.1");
  EXPECT_EQ(Opts.Server.WorldPort, 1);
  EXPECT_EQ(Opts.Server.ControlPort, 65535);
  EXPECT_EQ(Opts.Server.RequestPort, 30000);
  EXPECT_EQ(Opts.Server.Node.Workers, 1024u);
  EXPECT_EQ(Opts.OtlpEndpoint, "collector:4317");
}

TEST(Cli, ARepeatedFlagKeepsItsLastValue) {
  Options Opts;
  ASSERT_EQ(parse({"--workers", "2", "--workers", "3"}, Opts),
            ArgsResult::Parsed);
  EXPECT_EQ(Opts.Server.Node.Workers, 3u);
}

TEST(Cli, HelpWinsWhereverItStands) {
  EXPECT_EQ(parse({"--help"}), ArgsResult::Help);
  EXPECT_EQ(parse({"--workers", "4", "--help"}), ArgsResult::Help);
  // Parsing stops at `--help`, so what follows it is never read.
  EXPECT_EQ(parse({"--help", "--no-such-flag"}), ArgsResult::Help);
}

TEST(Cli, AFlagWithoutItsValueIsAnError) {
  for (const char *Flag : {"--bind", "--world-port", "--control-port",
                           "--request-port", "--workers", "--otlp"})
    EXPECT_EQ(parse({Flag}), ArgsResult::Error) << Flag;
}

TEST(Cli, APortMustBeAWholeNumberFrom1To65535) {
  for (const char *Flag : {"--world-port", "--control-port", "--request-port"})
    for (const char *Value : {"0", "65536", "-1", "12x", "", " 80", "+80"})
      EXPECT_EQ(parse({Flag, Value}), ArgsResult::Error)
          << Flag << " '" << Value << "'";
}

TEST(Cli, WorkersMustBeAWholeNumberFrom1To1024) {
  for (const char *Value : {"0", "1025", "-1", "12x", "", "4294967296"})
    EXPECT_EQ(parse({"--workers", Value}), ArgsResult::Error)
        << "'" << Value << "'";
}

TEST(Cli, AnUnknownArgumentIsAnError) {
  EXPECT_EQ(parse({"--no-such-flag"}), ArgsResult::Error);
  EXPECT_EQ(parse({"positional"}), ArgsResult::Error);
  EXPECT_EQ(parse({"--workers", "2", "-w"}), ArgsResult::Error);
}

TEST(Cli, TheUsageNamesEveryFlagAndDefault) {
  std::ostringstream Usage;
  printUsage(Usage);
  const std::string Text = Usage.str();
  for (const char *Flag : {"--bind", "--world-port", "--control-port",
                           "--request-port", "--workers", "--otlp", "--help"})
    EXPECT_NE(Text.find(Flag), std::string::npos) << Flag;
  const Options Defaults;
  EXPECT_NE(Text.find(Defaults.OtlpEndpoint), std::string::npos);
  EXPECT_NE(Text.find(std::to_string(Defaults.Server.WorldPort)),
            std::string::npos);
  EXPECT_NE(Text.find(std::to_string(Defaults.Server.ControlPort)),
            std::string::npos);
  EXPECT_NE(Text.find(std::to_string(Defaults.Server.RequestPort)),
            std::string::npos);
}
