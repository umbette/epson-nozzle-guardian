// Nozzle Guardian™ Smart Dongle — Parametric 3D Printable Enclosure
// Designed for COTS ESP32-S3 USB Dongle & Sensirion SHT40 Environmental Sensor
// License: CERN-OHL-P (Open Hardware) / MIT

$fn = 60;

// Dimensions (mm)
length = 56.0;      // Outer length
width  = 20.0;      // Outer width
height = 10.0;      // Outer height
wall   = 1.6;       // Shell wall thickness
fillet = 2.0;       // Rounded corner radius

usb_w = 12.5;       // USB-A connector width
usb_h = 4.8;        // USB-A connector height

pcb_l = 48.0;
pcb_w = 16.5;
pcb_h = 1.2;

// Rounded Box Helper
module rounded_box(l, w, h, r) {
    translate([r, r, r])
    minkowski() {
        cube([l - 2*r, w - 2*r, h - 2*r]);
        sphere(r=r);
    }
}

// Bottom Base Shell
module base_enclosure() {
    difference() {
        // Outer body
        rounded_box(length, width, height * 0.65, fillet);

        // Internal PCB cavity
        translate([wall, wall, wall])
            cube([length - 2*wall, width - 2*wall, height]);

        // USB-A opening on front face
        translate([-1, (width - usb_w)/2, wall + 0.5])
            cube([wall + 2, usb_w, usb_h]);

        // SHT40 Sensor Air Vent Grille on side face
        for (i = [0:3]) {
            translate([length - 16 + i*3, -1, wall + 1])
                cube([1.4, wall + 2, 3.5]);
        }
    }

    // PCB Support rails
    translate([wall, wall, wall])
        cube([pcb_l, 1.0, 1.5]);
    translate([wall, width - wall - 1.0, wall])
        cube([pcb_l, 1.0, 1.5]);

    // Snap-fit lip
    translate([wall + 0.2, wall + 0.2, height * 0.65 - 0.8])
        difference() {
            cube([length - 2*wall - 0.4, width - 2*wall - 0.4, 0.8]);
            translate([0.8, 0.8, -0.1])
                cube([length - 2*wall - 2.0, width - 2*wall - 2.0, 1.0]);
        }
}

// Top Snap-Fit Lid
module top_lid() {
    translate([0, width + 10, 0]) {
        difference() {
            // Lid outer shell
            rounded_box(length, width, height * 0.40, fillet);

            // Internal clearance
            translate([wall, wall, -0.1])
                cube([length - 2*wall, width - 2*wall, height * 0.40 - wall]);

            // RGB Status LED Light Pipe hole
            translate([length - 10, width/2, height * 0.40 - wall - 1])
                cylinder(h=wall + 2, r=1.2);

            // Embossed Logo text
            translate([length/2 - 12, width/2 - 2, height * 0.40 - 0.6])
                linear_extrude(height=1.0)
                    text("NG", size=5, font="Liberation Sans:style=Bold", halign="center");
        }
    }
}

// Render both parts ready for 3D printing (FDM 0.16mm layer height, PETG or PLA)
base_enclosure();
top_lid();
