#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <ctype.h>
#include <unistd.h>

#include <sys/socket.h>
#include <arpa/inet.h>
#include <errno.h>

#define BUFFER_SIZE 1024

/* Function prototypes */
void fatal(const char *comment);
void to_lower_case(char *s);
void strip_newline(char *s);
struct sockaddr *make_address(const char *host, int port);
void receive_loop(int port);
void send_loop(const char *host, int port);

/* 
 * Print an error message (comment), then perror() to show the system error,
 * and exit with a failure status.
 */
void fatal(const char *comment) {
    fprintf(stderr, "Error: %s\n", comment);
    perror(NULL);
    exit(EXIT_FAILURE);
}

/* 
 * Convert a C‐string to lowercase in place, e.g. "Hello\n" → "hello\n"
 */
void to_lower_case(char *s) {
    for (size_t i = 0; s[i] != '\0'; i++) {
        s[i] = (char) tolower((unsigned char) s[i]);
    }
}

/*
 * If the string ends with '\n', remove it.  E.g. "stop\n" → "stop"
 */
void strip_newline(char *s) {
    size_t len = strlen(s);
    if (len > 0 && s[len - 1] == '\n') {
        s[len - 1] = '\0';
    }
}

/*
 * Build a sockaddr_in for the given host+port.
 * If host == NULL, use INADDR_ANY (for a server bind).
 * Returns a malloc()'d struct sockaddr*, which the caller must free().
 */
struct sockaddr *make_address(const char *host, int port) {
    struct sockaddr_in *addr = malloc(sizeof(*addr));
    if (!addr) {
        fatal("malloc() failed in make_address");
    }

    memset(addr, 0, sizeof(*addr));
    addr->sin_family = AF_INET;
    addr->sin_port = htons(port);

    if (host == NULL) {
        addr->sin_addr.s_addr = htonl(INADDR_ANY);
    } else {
        if (inet_aton(host, &addr->sin_addr) == 0) {
            free(addr);
            fatal("inet_aton() failed to convert host");
        }
    }

    return (struct sockaddr *) addr;
}

/*
 * Listen on TCP port 'port', accept one connection, and print each received
 * line to stdout. Loop until a packet containing "stop" (case-insensitive).
 */
void receive_loop(int port) {
    /* 1. Create TCP socket */
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd < 0) {
        fatal("Unable to create socket in receive_loop");
    }

    /* 2. Allow quick reuse of the port after the program exits */
    int optval = 1;
    if (setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &optval, sizeof(optval)) < 0) {
        close(listen_fd);
        fatal("setsockopt(SO_REUSEADDR) failed in receive_loop");
    }

    /* 3. Bind to the given port on all interfaces */
    struct sockaddr *my_addr = make_address(NULL, port);
    if (bind(listen_fd, my_addr, sizeof(struct sockaddr_in)) < 0) {
        free(my_addr);
        close(listen_fd);
        fatal("bind() failed in receive_loop");
    }
    free(my_addr);

    /* 4. Start listening (backlog = 1) */
    if (listen(listen_fd, 1) < 0) {
        close(listen_fd);
        fatal("listen() failed in receive_loop");
    }

    printf("Waiting for a TCP client on port %d...\n", port);

    /* 5. Accept one connection */
    int conn_fd = accept(listen_fd, NULL, NULL);
    if (conn_fd < 0) {
        close(listen_fd);
        fatal("accept() failed in receive_loop");
    }

    /* We no longer need the listening socket */
    close(listen_fd);

    char buffer[BUFFER_SIZE];

    /* 6. Loop: recv() → print → check for "stop" */
    while (true) {
        ssize_t recv_len = recv(conn_fd, buffer, BUFFER_SIZE - 1, 0);
        if (recv_len < 0) {
            close(conn_fd);
            fatal("recv() failed in receive_loop");
        } else if (recv_len == 0) {
            /* Client closed the connection */
            break;
        }

        /* Null-terminate and print */
        buffer[recv_len] = '\0';
        printf("Received: %s\n", buffer);

        /* Prepare for comparison: strip newline and lowercase */
        strip_newline(buffer);
        to_lower_case(buffer);

        if (strcmp(buffer, "stop") == 0) {
            break;
        }
    }

    close(conn_fd);
}

/*
 * Connect to TCP host:port, then read lines from stdin and send each line
 * over the socket. Stop when the user types "stop" (case-insensitive).
 */
void send_loop(const char *host, int port) {
    /* 1. Create TCP socket */
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        fatal("Unable to create socket in send_loop");
    }

    /* 2. Build sockaddr for the server */
    struct sockaddr *server_addr = make_address(host, port);

    /* 3. Connect to the server */
    if (connect(sockfd, server_addr, sizeof(struct sockaddr_in)) < 0) {
        free(server_addr);
        close(sockfd);
        fatal("connect() failed in send_loop");
    }
    free(server_addr);

    char *buffer = NULL;
    size_t buffer_capacity = 0;

    /* 4. Read from stdin with getline() */
    while (true) {
        ssize_t bytes_read = getline(&buffer, &buffer_capacity, stdin);
        if (bytes_read < 0) {
            free(buffer);
            close(sockfd);
            fatal("getline() from stdin failed in send_loop");
        }

        /* Strip newline so we can check for "stop" cleanly */
        strip_newline(buffer);
        to_lower_case(buffer);

        /* Send exactly the characters up to '\0' + a newline */
        size_t to_send = strlen(buffer);
        /* Re-add a newline in the packet so the server can display line breaks */
        char send_buffer[BUFFER_SIZE];
        snprintf(send_buffer, BUFFER_SIZE, "%s\n", buffer);

        ssize_t bytes_sent = send(sockfd, send_buffer, to_send + 1, 0);
        if (bytes_sent < 0) {
            free(buffer);
            close(sockfd);
            fatal("send() failed in send_loop");
        }

        if (strcmp(buffer, "stop") == 0) {
            break;
        }
    }

    free(buffer);
    close(sockfd);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(
            stderr,
            "Usage:\n"
            "  %s -l <port>      # listen on TCP port <port>\n"
            "  %s <host> <port>  # send lines to TCP host:port\n",
            argv[0],
            argv[0]
        );
        exit(EXIT_FAILURE);
    }

    /* Parse the port number */
    char *endptr = NULL;
    int port = (int) strtol(argv[2], &endptr, 10);
    if (endptr == argv[2] || port <= 0 || port > 65535) {
        fprintf(stderr, "Invalid port: %s\n", argv[2]);
        exit(EXIT_FAILURE);
    }

    /* Lowercase the first argument so "-L" works the same as "-l" */
    to_lower_case(argv[1]);

    if (strncmp(argv[1], "-l", 2) == 0) {
        receive_loop(port);
    } else {
        /* argv[1] is interpreted as host */
        send_loop(argv[1], port);
    }

    return EXIT_SUCCESS;
}

