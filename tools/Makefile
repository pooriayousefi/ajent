SERVERS := directory-mcp-server file-mcp-server git-mcp-server \
           memory-mcp-server random-number-mcp-server shell-mcp-server \
           statistics-mcp-server time-mcp-server utility-mcp-server

.PHONY: all $(SERVERS)
all: $(SERVERS)

$(SERVERS):
	$(MAKE) -C $@
