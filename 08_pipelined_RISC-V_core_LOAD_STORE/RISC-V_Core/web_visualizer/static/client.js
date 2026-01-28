const socket = io();

function sendCommand(action) {
    socket.emit('command', { action: action });
}

socket.on('update', (data) => {
    // Basic Text Updates
    document.getElementById('cycle-display').innerText = data.cycle || 0;
    updateSVG(data);
});

function updateSVG(data) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    // Helper to safely set text content
    const setText = (id, val) => {
        const el = svg.getElementById(id);
        if (el) el.textContent = val;
    };

    // Helper to color boxes (Stall/Flush)
    const setBoxColor = (id, color, stroke) => {
        const el = svg.getElementById(id);
        if (el) {
            el.style.fill = color;
            el.style.stroke = stroke;
        }
    };

    // --- 1. UPDATE TEXT FIELDS ---
    // PC & ASM (Uses the new enriched data from server)
    if (data.asm) {
        setText('txt-asm-if', data.asm.if);
        setText('txt-asm-id', data.asm.id);
        setText('txt-asm-ex', data.asm.ex);
        setText('txt-asm-mem', data.asm.mem);
        setText('txt-asm-wb', data.asm.wb);
    }
    if (data.pc_hex) {
        setText('txt-pc-if', "PC: " + data.pc_hex.if);
    }

    // ID Details
    if (data.id) {
        setText('txt-rs1-id', `rs1: x${data.id.rs1}`);
        setText('txt-rs2-id', `rs2: x${data.id.rs2}`);
        setText('txt-rd-id',  `-> rd: x${data.id.rd}`);
    }

    // EX Details
    if (data.ex) {
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // MEM Details
    if (data.mem) {
        // Show Address if writing or reading
        const isActive = data.mem.we || data.mem.mem_rd_op;
        setText('txt-mem-addr', isActive ? `Addr: 0x${Number(data.mem.addr).toString(16)}` : "Addr: --");
        setText('txt-mem-data', data.mem.we ? `Wr: 0x${Number(data.mem.wdata).toString(16)}` : "Data: --");
    }

    // WB Details
    if (data.wb) {
        if (data.wb.we && data.wb.rd != 0) {
            setText('txt-wb-reg', `x${data.wb.rd} = 0x${Number(data.wb.wdata).toString(16)}`);
        } else {
            setText('txt-wb-reg', "--");
        }
    }

    // --- 2. VISUALIZE HAZARDS ---
    // Reset colors
    ['if','id','ex','mem','wb'].forEach(s => setBoxColor(`stage-${s}`, 'white', '#333'));

    // Hazards (Red Box)
    if (data.hazard) {
        if (data.hazard.if_stall) setBoxColor('stage-if', '#ffcccc', 'red');
        if (data.hazard.id_stall) setBoxColor('stage-id', '#ffcccc', 'red');

        // Flushes (Orange Box)
        if (data.hazard.flush) {
             setBoxColor('stage-id', '#ffe5cc', 'orange');
             setBoxColor('stage-ex', '#ffe5cc', 'orange');
        }
    }

    // --- 3. FORWARDING ARROWS ---
    // Assuming data.fwd.a_sel == 1 (MEM), == 2 (WB)
    // You might need to adjust these checks based on your exact JSON structure
    const fwdExId = svg.getElementById('fwd-ex-id'); // Not typically used but available
    const fwdMemId = svg.getElementById('fwd-mem-id'); // Forwarding from MEM
    const fwdWbId  = svg.getElementById('fwd-wb-id');  // Forwarding from WB

    // Reset Arrows
    [fwdMemId, fwdWbId].forEach(el => {
        if(el) { el.style.stroke = "#ddd"; el.style.strokeWidth = "3"; }
    });

    if (data.fwd) {
        // If Forwarding from MEM detected
        if (data.fwd.a_sel == 1 || data.fwd.b_sel == 1) {
            if(fwdMemId) { fwdMemId.style.stroke = "blue"; fwdMemId.style.strokeWidth = "5"; }
        }
        // If Forwarding from WB detected
        if (data.fwd.a_sel == 2 || data.fwd.b_sel == 2) {
            if(fwdWbId) { fwdWbId.style.stroke = "green"; fwdWbId.style.strokeWidth = "5"; }
        }
    }
}