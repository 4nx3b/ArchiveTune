#!/usr/bin/env python3
"""Minimal Java class-file parser: dumps interface method names + descriptors."""
import struct
import sys


def parse(path):
    data = open(path, "rb").read()
    pos = [0]

    def u1():
        v = data[pos[0]]
        pos[0] += 1
        return v

    def u2():
        v = struct.unpack_from(">H", data, pos[0])[0]
        pos[0] += 2
        return v

    def u4():
        v = struct.unpack_from(">I", data, pos[0])[0]
        pos[0] += 4
        return v

    assert u4() == 0xCAFEBABE
    u2(); u2()  # minor, major
    cp_count = u2()
    consts = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = u1()
        if tag == 1:  # Utf8
            length = u2()
            consts[i] = data[pos[0]:pos[0] + length].decode("utf-8", "replace")
            pos[0] += length
        elif tag in (7, 8):  # Class, String
            consts[i] = ("ref", u2())
        elif tag in (3, 4):  # Integer, Float
            pos[0] += 4
        elif tag in (5, 6):  # Long, Double
            pos[0] += 8
            i += 1
        elif tag in (9, 10, 11, 12, 17, 18):  # Field/Method/InterfaceMethod/NameType/Dynamic/InvokeDynamic
            consts[i] = ("ref2", u2(), u2())
        elif tag == 15:  # MethodHandle
            pos[0] += 3
        elif tag == 16:  # MethodType
            pos[0] += 2
        else:
            raise ValueError(f"unknown tag {tag} at {i}")
        i += 1

    def utf8(idx):
        return consts[idx] if isinstance(consts[idx], str) else f"<{consts[idx]}>"

    u2(); u2(); u2()  # access, this, super
    ifaces_count = u2()
    ifaces = [utf8(u2()) for _ in range(ifaces_count)]
    # fields
    for _ in range(u2()):
        u2(); u2(); u2()
        for _ in range(u2()):
            u2()
            pos[0] += u4()  # skip attribute content
    print(f"// {path}")
    for name in ifaces:
        print(f"// extends {name}")
    for _ in range(u2()):
        access = u2()
        name_i = u2()
        desc_i = u2()
        name = utf8(name_i)
        desc = utf8(desc_i)
        for _ in range(u2()):
            u2()
            pos[0] += u4()  # skip attribute content
        flags = []
        if access & 0x0001: flags.append("public")
        if access & 0x0008: flags.append("static")
        if access & 0x0010: flags.append("final")
        if access & 0x0400: flags.append("abstract")
        print("  ".join(flags), name, desc)


for p in sys.argv[1:]:
    parse(p)
    print()
