package(default_visibility = ["//visibility:public"])

load("/tools/rules/pex/pex_rules", "pex_library")

pex_library(
  name = 'statemgr-py',
  srcs = glob(['**/*.py']),
  deps = [
    '//heron/proto:proto-py',
  ],
  reqs = [
    'PyYAML==3.10',
    'kazoo==1.3.1',
    'zope.interface==4.0.5'
  ],
)
