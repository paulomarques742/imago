#!/usr/bin/env python3
"""Lists the opcode lists and colour calibration of DNG files.

LibRaw 0.22.2 without the Adobe DNG SDK reads OpcodeList1/2/3 into
imgdata.color.dng_levels.rawopcodes but applies none of them, so the lens corrections a phone
writes into its DNGs (GainMap, WarpRectilinear, FixVignetteRadial) are missing from IMAGO's
development until IMAGO applies them itself. This shows what a given file carries.

Usage: python dng_opcodes.py FILE_OR_FOLDER [...]
Standard library only; reads the TIFF structure, never the sensor data.
"""
import os
import struct
import sys

OPCODES = {
    1: "WarpRectilinear", 2: "WarpFisheye", 3: "FixVignetteRadial", 4: "FixBadPixelsConstant",
    5: "FixBadPixelsList", 6: "TrimBounds", 7: "MapTable", 8: "MapPolynomial", 9: "GainMap",
    10: "DeltaPerRow", 11: "DeltaPerColumn", 12: "ScalePerRow", 13: "ScalePerColumn",
    14: "WarpRectilinear2",
}
LISTS = {51008: "OpcodeList1", 51009: "OpcodeList2", 51022: "OpcodeList3"}
SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4}
ILLUMINANTS = {17: "Standard A", 21: "D65", 23: "D50", 255: "Other"}


class Tiff:
    def __init__(self, data):
        self.data = data
        if data[:2] == b"II":
            self.order = "<"
        elif data[:2] == b"MM":
            self.order = ">"
        else:
            raise ValueError("not a TIFF/DNG file")

    def unpack(self, fmt, offset):
        return struct.unpack_from(self.order + fmt, self.data, offset)

    def ifd(self, offset):
        """Tags as {id: (type, count, value bytes)}, and the next IFD's offset."""
        (count,) = self.unpack("H", offset)
        tags = {}
        for i in range(count):
            tag, kind, n = self.unpack("HHI", offset + 2 + i * 12)
            size = SIZES.get(kind, 1) * n
            where = offset + 2 + i * 12 + 8
            if size > 4:
                (where,) = self.unpack("I", where)
            tags[tag] = (kind, n, self.data[where:where + size])
        (following,) = self.unpack("I", offset + 2 + count * 12)
        return tags, following

    def values(self, entry):
        kind, n, raw = entry
        if kind == 2:
            return raw.split(b"\0")[0].decode("latin-1")
        code = {1: "B", 3: "H", 4: "I", 6: "b", 7: "B", 8: "h", 9: "i", 11: "f", 12: "d", 13: "I"}
        if kind in (5, 10):
            pairs = struct.unpack(self.order + ("I" if kind == 5 else "i") * (2 * n), raw)
            return [pairs[i] / pairs[i + 1] if pairs[i + 1] else 0.0 for i in range(0, len(pairs), 2)]
        return list(struct.unpack(self.order + code[kind] * n, raw))


def describe_opcode(opcode, params):
    """The parameters that matter for a lens correction; opcode data is always big-endian."""
    try:
        if opcode == 9:
            top, left, bottom, right, plane, planes, row_pitch, col_pitch, points_v, points_h = \
                struct.unpack_from(">10I", params, 0)
            map_planes = struct.unpack_from(">I", params, 72)[0]
            gains = struct.unpack_from(">%df" % (points_v * points_h * map_planes), params, 76)
            return (f"area {left},{top}-{right},{bottom}, plane {plane}+{planes}, pitch {row_pitch}x{col_pitch}, "
                    f"map {points_h}x{points_v}x{map_planes}, gain {min(gains):.3f}..{max(gains):.3f}")
        if opcode == 1:
            (planes,) = struct.unpack_from(">I", params, 0)
            coefficients = [struct.unpack_from(">6d", params, 4 + 48 * p) for p in range(planes)]
            centre = struct.unpack_from(">2d", params, 4 + 48 * planes)
            text = "; ".join("kr " + " ".join(f"{k:.5f}" for k in c[:4]) + " kt " + " ".join(f"{k:.5f}" for k in c[4:])
                             for c in coefficients)
            return f"{planes} plane(s): {text}; centre {centre[0]:.4f},{centre[1]:.4f}"
        if opcode == 3:
            k = struct.unpack_from(">7d", params, 0)
            return "k " + " ".join(f"{v:.5f}" for v in k[:5]) + f"; centre {k[5]:.4f},{k[6]:.4f}"
    except struct.error:
        return "truncated parameters"
    return ""


def describe_list(name, raw):
    (count,) = struct.unpack_from(">I", raw, 0)
    print(f"    {name}: {count} opcode(s)")
    offset = 4
    for _ in range(count):
        opcode, version, flags, size = struct.unpack_from(">4I", raw, offset)
        params = raw[offset + 16:offset + 16 + size]
        optional = "optional" if flags & 1 else "required"
        preview = ", skip in previews" if flags & 2 else ""
        label = OPCODES.get(opcode, f"unknown {opcode}")
        print(f"      {label} ({optional}{preview}, DNG {version >> 24}.{(version >> 16) & 255}) "
              f"{describe_opcode(opcode, params)}".rstrip())
        offset += 16 + size


def describe(path):
    with open(path, "rb") as handle:
        tiff = Tiff(handle.read())
    print(path)
    pending = [(tiff.unpack("I", 4)[0], "IFD0")]
    seen = set()
    while pending:
        offset, label = pending.pop(0)
        if not offset or offset in seen or offset >= len(tiff.data):
            continue
        seen.add(offset)
        tags, following = tiff.ifd(offset)
        pending.append((following, f"IFD after {label}"))
        for i, sub in enumerate(tiff.values(tags[330]) if 330 in tags else []):
            pending.append((sub, f"{label} SubIFD {i}"))
        kind = tiff.values(tags[254])[0] if 254 in tags else 0
        role = "main image" if kind == 0 else "preview" if kind & 1 else f"subfile type {kind}"
        size = ""
        if 256 in tags and 257 in tags:
            size = f" {tiff.values(tags[256])[0]}x{tiff.values(tags[257])[0]}"
        print(f"  {label}: {role}{size}")
        for tag, name in (271, "Make"), (272, "Model"), (50708, "UniqueCameraModel"):
            if tag in tags:
                print(f"    {name}: {tiff.values(tags[tag])}")
        for index, (matrix, illuminant) in enumerate(((50721, 50778), (50722, 50779)), start=1):
            if matrix in tags:
                light = tiff.values(tags[illuminant])[0] if illuminant in tags else None
                print(f"    ColorMatrix{index} for {ILLUMINANTS.get(light, light)}")
        if 50728 in tags:
            print("    AsShotNeutral " + " ".join(f"{v:.4f}" for v in tiff.values(tags[50728])))
        for tag, name in LISTS.items():
            if tag in tags:
                describe_list(name, tags[tag][2])


def main(arguments):
    if not arguments:
        print(__doc__.strip())
        return 2
    for argument in arguments:
        paths = [os.path.join(argument, name) for name in sorted(os.listdir(argument))] \
            if os.path.isdir(argument) else [argument]
        for path in paths:
            if path.lower().endswith(".dng") or not os.path.isdir(argument):
                try:
                    describe(path)
                except (ValueError, struct.error, KeyError) as error:
                    print(f"{path}: {error}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
