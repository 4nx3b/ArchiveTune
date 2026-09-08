#!/usr/bin/env python3
"""Minimal JVM class file parser: dumps method name+descriptor pairs."""
import struct
import sys


def parse(path):
    data = open(path, 'rb').read()
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    pos = 8
    count = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    pool = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from('>H', data, pos)[0]
            pos += 2
            pool[i] = data[pos:pos + length].decode('utf-8', 'replace')
            pos += length
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pos += 2
        elif tag in (3, 4):  # Integer, Float
            pos += 4
        elif tag in (5, 6):  # Long, Double (takes two slots)
            pos += 8
            i += 1
        elif tag == 15:  # MethodHandle
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):  # refs
            pos += 4
        else:
            raise ValueError(f'unknown tag {tag} at {pos}')
        i += 1
    access = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    this_class = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    super_class = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    ifaces = struct.unpack_from('>H', data, pos)[0]
    pos += 2 + 2 * ifaces
    fields = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    pos = skip_members(data, pos, fields)
    methods = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    out = []
    for _ in range(methods):
        m_access, name_idx, desc_idx = struct.unpack_from('>HHH', data, pos)
        pos += 6
        attrs = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        pos = skip_members_attrs(data, pos, attrs)
        out.append((pool[name_idx], pool[desc_idx], m_access))
    return pool[this_class], out


def skip_members(data, pos, count):
    for _ in range(count):
        pos += 6
        attrs = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        pos = skip_members_attrs(data, pos, attrs)
    return pos


def skip_members_attrs(data, pos, count):
    for _ in range(count):
        name_idx = struct.unpack_from('>H', data, pos)[0]
        length = struct.unpack_from('>I', data, pos + 2)[0]
        pos += 6 + length
    return pos


for path in sys.argv[1:]:
    cls, methods = parse(path)
    print(f'=== {cls} ===')
    for name, desc, access in methods:
        if name in ('<clinit>',):
            continue
        visibility = 'public' if access & 0x0001 else 'private'
        print(f'  {visibility} {name}  {desc}')
    print()
