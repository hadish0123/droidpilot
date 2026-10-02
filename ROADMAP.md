# PRIME P6 Production Roadmap

Baseline from the architecture/security audit: **26%** against the full platform scope.

PRIME should only be called **100% complete** when all production acceptance scenarios are reproducibly green on supported Android devices and external services.

## 1. Security foundation
- [x] Mandatory Device Bridge authentication
- [x] Keystore-backed per-install bridge token
- [x] MCP bridge client requires authentication
- [x] Disable globally allowed Android cleartext client traffic
- [x] CI validation

## 2. AI and Tool architecture
- [x] AI Provider contract
- [x] OpenAI Responses provider extraction
- [x] Model Registry / selection policy
- [x] Typed Tool Registry with risk metadata
- [x] Unknown-tool guard
- [ ] Runtime contextual confirmation policy

## 3. Context and Chat
- [ ] Context Manager with token-aware budgeting
- [ ] Chat repository and DB migrations
- [ ] Smooth streaming UI
- [ ] Stop generation
- [ ] Retry / regenerate / edit / branch
- [ ] Markdown and code blocks
- [ ] Search / archive / pin / export
- [ ] Attachment model

## 4. Files, Search and Vision
- [ ] File picker and attachment pipeline
- [ ] PDF extraction, chunking and search
- [ ] TXT / Markdown / JSON / CSV readers
- [ ] DOCX / XLSX / PPTX adapters
- [ ] Web Search abstraction with citations
- [ ] Image/vision attachments

## 5. Plugins, MCP Client and Connectors
- [ ] Plugin manifest, lifecycle and permissions
- [ ] Plugin SDK
- [ ] MCP client/server registry
- [ ] MCP tool/resource/prompt discovery
- [ ] Connector auth and encrypted credential storage
- [ ] Health checks, reconnect and error recovery

## 6. Memory
- [ ] Preferences/profile memory
- [ ] Conversation summaries
- [ ] Project memory
- [ ] Embeddings/vector index
- [ ] Privacy controls, delete and export

## 7. Tasks and Background work
- [ ] WorkManager task engine
- [ ] Scheduled/conditional work
- [ ] Retry/backoff/cancellation
- [ ] Notification results

## 8. Voice, Images and UX hardening
- [ ] Partial/streaming transcription
- [ ] Speech provider abstraction
- [ ] Vision provider abstraction
- [ ] RTL/LTR mixed-content quality
- [ ] Accessibility UX review

## 9. Observability and Developer mode
- [ ] Structured logs and request IDs
- [ ] Token/usage/cost accounting
- [ ] Provider/tool latency metrics
- [ ] Developer diagnostics panel
- [ ] Privacy-safe diagnostics export

## 10. Security hardening
- [ ] Runtime risk/confirmation policy
- [ ] Prompt-injection trust boundaries
- [ ] Conversation encryption option
- [ ] Secret/dependency/code scanning
- [ ] Threat model
- [ ] Backup/export policy

## 11. Testing and CI
- [ ] Provider contract tests
- [ ] Tool policy tests
- [ ] DB migration tests
- [ ] File/search/vision tests
- [ ] Android instrumentation tests
- [ ] Physical-device acceptance suite
- [ ] Release build/signing checks
- [ ] SBOM/dependency audit

## 12. Production acceptance
Required acceptance scenarios include:
- calculator/tool routing
- PDF summarization
- file search
- sourced web search
- image understanding
- plugin installation and routing
- MCP server discovery
- stop generation
- timeout recovery
- long-conversation stability

Do not close the roadmap or report 100% until these scenarios pass end-to-end.
