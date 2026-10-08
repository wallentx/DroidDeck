/* ELF64 using the i386 syscall ABI. Run natively and through Box64. */
.section .text
.global _start
_start:
    mov $1, %r12d
    mov $39, %eax
    syscall
    test %eax, %eax
    jle failed
    mov %eax, %r15d
    mov $20, %eax
    int $0x80
    cmp %r15d, %eax
    jne failed

    mov $2, %r12d
    /* The compat ABI must ignore pointer bits above bit 31. */
    lea name(%rip), %rbx
    mov $1, %rax
    shl $32, %rax
    or %rax, %rbx
    mov $2, %ecx
    mov $356, %eax
    int $0x80
    test %eax, %eax
    js failed
    mov %eax, %r14d

    mov $3, %r12d
    mov %r14d, %ebx
    lea payload(%rip), %rcx
    mov $3, %edx
    mov $4, %eax
    int $0x80
    cmp $3, %eax
    jne failed

    mov $4, %r12d
    /* Native ABI operates on the descriptor returned by the compat ABI. */
    mov %r14d, %edi
    xor %esi, %esi
    xor %edx, %edx
    mov $8, %eax
    syscall
    test %rax, %rax
    jne failed
    mov %r14d, %ebx
    lea buffer(%rip), %rcx
    mov $3, %edx
    mov $3, %eax
    int $0x80
    cmp $3, %eax
    jne failed
    cmpw $0x4546, buffer(%rip)
    jne failed
    cmpb $0x58, buffer+2(%rip)
    jne failed

    mov $5, %r12d
    mov %r14d, %ebx
    mov $6, %eax
    int $0x80
    test %eax, %eax
    jne failed
    mov $6, %eax
    int $0x80
    cmp $-9, %eax                 /* EBADF */
    jne failed

    mov $6, %r12d
    lea name(%rip), %rbx
    mov $-1, %ecx
    mov $356, %eax
    int $0x80
    cmp $-22, %eax                /* EINVAL */
    jne failed

    mov $7, %r12d
    mov $1, %ebx                  /* Invalid userspace pointer */
    xor %ecx, %ecx
    mov $356, %eax
    int $0x80
    cmp $-14, %eax                /* EFAULT */
    jne failed

    mov $8, %r12d
    mov $-1, %ebx
    lea buffer(%rip), %rcx
    mov $1, %edx
    mov $3, %eax
    int $0x80
    cmp $-9, %eax
    jne failed
    mov $4, %eax
    int $0x80
    cmp $-9, %eax
    jne failed

    mov $9, %r12d
    mov $-1, %eax
    int $0x80
    cmp $-38, %eax                /* Unsupported calls must remain ENOSYS. */
    jne failed

    mov $10, %r12d
    lea null_path(%rip), %rbx
    mov $0x88001, %ecx            /* O_WRONLY | O_LARGEFILE | O_CLOEXEC */
    xor %edx, %edx
    mov $5, %eax
    int $0x80
    test %eax, %eax
    js failed
    mov %eax, %r14d
    mov %eax, %edi
    mov $1, %esi                  /* F_GETFD through the native ABI */
    mov $72, %eax
    syscall
    test $1, %eax                 /* FD_CLOEXEC survived flag conversion */
    jz failed
    mov %r14d, %ebx
    lea payload(%rip), %rcx
    mov $3, %edx
    mov $4, %eax
    int $0x80
    cmp $3, %eax
    jne failed
    mov $6, %eax
    int $0x80
    test %eax, %eax
    jne failed

    mov $11, %r12d
    lea missing_path(%rip), %rbx
    xor %ecx, %ecx
    xor %edx, %edx
    mov $5, %eax
    int $0x80
    cmp $-2, %eax                 /* ENOENT */
    jne failed
    xor %ebx, %ebx
    mov $5, %eax
    int $0x80
    cmp $-14, %eax                /* EFAULT for a null path */
    jne failed

    mov $1, %eax
    mov $1, %edi
    lea passed(%rip), %rsi
    mov $passed_len, %edx
    syscall
    xor %edi, %edi
    jmp exit
failed:
    mov %r12d, %edi              /* The exit status identifies the failed stage. */
exit:
    mov $60, %eax
    syscall
.section .rodata
null_path: .asciz "/dev/null"
missing_path: .asciz "/droiddeck-box64-test-file-does-not-exist"
name: .asciz "droiddeck-compat-syscall-test"
payload: .ascii "FEX"
passed: .ascii "PASS: int80 getpid, memfd, open, IO, errors, pointer truncation\n"
.set passed_len, .-passed
.section .bss
buffer: .skip 3
.section .note.GNU-stack,"",@progbits
