.section .text
.global _start
_start:
    mov $39, %eax
    syscall
    test %rax, %rax
    jle failed
    mov $1, %eax
    mov $1, %edi
    lea native_ok(%rip), %rsi
    mov $native_ok_len, %edx
    syscall
#ifdef MIXED_ABI
    mov $20, %eax
    int $0x80
    test %eax, %eax
    jle failed
    lea name(%rip), %rbx
    mov $2, %ecx
    mov $356, %eax
    int $0x80
#else
    lea name(%rip), %rdi
    mov $2, %esi
    mov $319, %eax
    syscall
#endif
    test %eax, %eax
    js failed
#ifdef MIXED_ABI
    mov %eax, %ebx
    mov $6, %eax
    int $0x80
#else
    mov %eax, %edi
    mov $3, %eax
    syscall
#endif
    test %eax, %eax
    jne failed
    mov $1, %eax
    mov $1, %edi
    lea passed(%rip), %rsi
    mov $passed_len, %edx
    syscall
    xor %edi, %edi
    jmp exit
failed:
    mov $1, %edi
exit:
    mov $60, %eax
    syscall
.section .rodata
native_ok: .ascii "NATIVE_X64_GETPID_OK\n"
.set native_ok_len, .-native_ok
passed: .ascii "GETPID_MEMFD_CLOSE_PASS\n"
.set passed_len, .-passed
name: .asciz "fex-mixed-abi-probe"
.section .note.GNU-stack,"",@progbits
