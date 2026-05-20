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
 * Print an error message (comment), then perror() to show system error,
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
 * Listen on UDP port 'port', printing each received line to stdout.
 * Loop until a packet containing "stop" (case-insensitive) arrives.
 */
void receive_loop(int port) {
    /* 1. Create UDP socket */
    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        fatal("Unable to create socket in receive_loop");
    }

    /* 2. Bind to the given port on all interfaces */
    struct sockaddr *my_addr = make_address(NULL, port);
    if (bind(sockfd, my_addr, sizeof(struct sockaddr_in)) < 0) {
        free(my_addr);
        close(sockfd);
        fatal("bind() failed in receive_loop");
    }
    free(my_addr);

    char buffer[BUFFER_SIZE];

    /* 3. Loop: recvfrom() → print → check for "stop" */
    while (true) {
        ssize_t recv_len = recvfrom(sockfd, buffer, BUFFER_SIZE - 1, 0, NULL, NULL);
        if (recv_len < 0) {
            close(sockfd);
            fatal("recvfrom() failed in receive_loop");
        }

        /* Null-terminate and print */
        buffer[recv_len] = '\0';
        printf("Received: %s", buffer);

        /* Prepare for comparison: strip newline and lowercase */
        strip_newline(buffer);
        to_lower_case(buffer);

        if (strcmp(buffer, "stop") == 0) {
            break;
        }
    }

    close(sockfd);
}

/*
 * Read lines from stdin, send each as a UDP packet to host:port.
 * Stop when the user types "stop" (case-insensitive).
 */
void send_loop(const char *host, int port) {
    /* 1. Create UDP socket */
    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        fatal("Unable to create socket in send_loop");
    }

    /* 2. Build sockaddr for the peer */
    struct sockaddr *peer_addr = make_address(host, port);

    /* 3. Read from stdin with getline() */
    char *buffer = NULL;
    size_t buffer_capacity = 0;

    while (true) {
        ssize_t bytes_read = getline(&buffer, &buffer_capacity, stdin);
        if (bytes_read < 0) {
            free(buffer);
            free(peer_addr);
            close(sockfd);
            fatal("getline() from stdin failed in send_loop");
        }

        /* Strip newline so we can check for "stop" cleanly */
        strip_newline(buffer);
        to_lower_case(buffer);

        /* Send exactly the characters up to '\0' */
        ssize_t bytes_sent = sendto(
            sockfd,
            buffer,
            strlen(buffer),
            0,
            peer_addr,
            sizeof(struct sockaddr_in)
        );
        if (bytes_sent < 0) {
            free(buffer);
            free(peer_addr);
            close(sockfd);
            fatal("sendto() failed in send_loop");
        }

        if (strcmp(buffer, "stop") == 0) {
            break;
        }
    }

    free(buffer);
    free(peer_addr);
    close(sockfd);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(
            stderr,
            "Usage:\n"
            "  %s -l <port>      # listen on UDP port <port>\n"
            "  %s <host> <port>  # send lines to UDP host:port\n",
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

