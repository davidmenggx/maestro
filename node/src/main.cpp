#include <csignal>
#include <cstddef>
#include <exception>
#include <iostream>
#include <span>

#include <asio/io_context.hpp>
#include <asio/signal_set.hpp>

#include "maestro/cli.hpp"
#include "maestro/net/server.hpp"

int main(int Argc, char **Argv) {
  using maestro::cli::ArgsResult;
  maestro::cli::Options Opts;
  switch (maestro::cli::parseArgs(
      std::span<char *const>(Argv, static_cast<std::size_t>(Argc)), Opts)) {
  case ArgsResult::Help:
    maestro::cli::printUsage(std::cout);
    return 0;
  case ArgsResult::Error:
    maestro::cli::printUsage(std::cerr);
    return 2;
  case ArgsResult::Parsed:
    break;
  }

  try {
    asio::io_context Io;
    maestro::net::Server Node(Io, Opts.Server);
    asio::signal_set Signals(Io, SIGINT, SIGTERM);
    Signals.async_wait([&](const asio::error_code &, int) { Node.stop(); });
    std::clog << "maestro-node: listening on " << Opts.Server.Bind << ", world "
              << Node.worldPort() << ", control " << Node.controlPort()
              << ", requests " << Node.requestPort() << ", "
              << Opts.Server.Node.Workers << " workers\n";
    Io.run();
    std::clog << "maestro-node: stopped after " << Node.searches()
              << " searches\n";
  } catch (const std::exception &Error) {
    std::cerr << "maestro-node: " << Error.what() << '\n';
    return 1;
  }
  return 0;
}
