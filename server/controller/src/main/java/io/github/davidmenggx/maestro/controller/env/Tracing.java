package io.github.davidmenggx.maestro.controller.env;

/** Where the controller reports each sampled request once its mob has its outcome. */
public interface Tracing {

  /** Called on the controller thread. */
  void finished(RequestTrace trace);
}
